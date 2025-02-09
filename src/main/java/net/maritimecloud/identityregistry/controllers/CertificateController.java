/*
 * Copyright 2017 Danish Maritime Authority.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package net.maritimecloud.identityregistry.controllers;

import lombok.extern.slf4j.Slf4j;
import net.maritimecloud.identityregistry.model.database.Certificate;
import net.maritimecloud.identityregistry.services.CertificateService;
import net.maritimecloud.identityregistry.utils.CertificateUtil;
import net.maritimecloud.pki.CertificateHandler;
import net.maritimecloud.pki.PKIConstants;
import net.maritimecloud.pki.Revocation;
import net.maritimecloud.pki.RevocationInfo;
import org.bouncycastle.cert.ocsp.BasicOCSPRespBuilder;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.Req;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.UnknownStatus;
import org.bouncycastle.util.encoders.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.cert.CRLException;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping(value={"oidc", "x509"})
@Slf4j
public class CertificateController {
    private CertificateService certificateService;

    @Autowired
    public void setCertificateService(CertificateService certificateService) {
        this.certificateService = certificateService;
    }

    private CertificateUtil certUtil;

    @Autowired
    public void setCertUtil(CertificateUtil certUtil) {
        this.certUtil = certUtil;
    }

    /**
     * Returns info about the device identified by the given ID
     * 
     * @return a reply...
     */
    @RequestMapping(
            value = "/api/certificates/crl/{caAlias}",
            method = RequestMethod.GET,
            produces = "application/x-pem-file;charset=UTF-8")
    @ResponseBody
    public ResponseEntity<?> getCRL(@PathVariable String caAlias) {
        // If looking for the root CRL we load that from a file and return it.
        if (PKIConstants.ROOT_CERT_ALIAS.equals(caAlias)) {
            try {
                String rootCrl = new String(Files.readAllBytes(Paths.get(certUtil.getRootCrlPath())), StandardCharsets.UTF_8);
                return new ResponseEntity<>(rootCrl, HttpStatus.OK);
            } catch (IOException e) {
                log.error("Unable to get load root crl file", e);
                return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }
        X509Certificate caCert = (X509Certificate) certUtil.getKeystoreHandler().getMCCertificate(caAlias);
        if (caCert == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        List<Certificate> revokedCerts = this.certificateService.listRevokedCertificate(caAlias);
        List<RevocationInfo> revocationInfos = new ArrayList<>();
        for (Certificate cert : revokedCerts) {
            revocationInfos.add(cert.toRevocationInfo());
        }
        X509CRL crl = Revocation.generateCRL(revocationInfos, certUtil.getKeystoreHandler().getSigningCertEntry(caAlias));
        try {
            String pemCrl = CertificateHandler.getPemFromEncoded("X509 CRL", crl.getEncoded());
            return new ResponseEntity<>(pemCrl, HttpStatus.OK);
        } catch (CRLException e) {
            log.error("Unable to get Pem from bytes", e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(
            value = "/api/certificates/ocsp/{caAlias}",
            method = RequestMethod.POST,
            consumes = "application/ocsp-request",
            produces = "application/ocsp-response")
    @ResponseBody
    public ResponseEntity<?> postOCSP(@PathVariable String caAlias, @RequestBody byte[] input) {
        byte[] byteResponse;
        try {
            byteResponse = handleOCSP(input, caAlias);
        } catch (IOException e) {
            log.error("Failed to update OCSP", e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new ResponseEntity<>(byteResponse, HttpStatus.OK);
    }

    @RequestMapping(
            value = "/api/certificates/ocsp/{caAlias}/**",
            method = RequestMethod.GET,
            produces = "application/ocsp-response")
    @ResponseBody
    public ResponseEntity<?> getOCSP(HttpServletRequest request, @PathVariable String caAlias) {
        String uri = request.getRequestURI();
        String encodedOCSP = uri.substring(uri.indexOf(caAlias) + caAlias.length() + 1);
        try {
            encodedOCSP = URLDecoder.decode(encodedOCSP, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            log.error("Failed to URL decode OCSP", e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        byte[] decodedOCSP = Base64.decode(encodedOCSP);
        byte[] byteResponse;
        try {
            byteResponse = handleOCSP(decodedOCSP, caAlias);
        } catch (IOException e) {
            log.error("Failed to base64 decode OCSP", e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new ResponseEntity<>(byteResponse, HttpStatus.OK);
    }

    protected byte[] handleOCSP(byte[] input, String certAlias) throws IOException {
        OCSPReq ocspreq = new OCSPReq(input);
        /* TODO: verify signature - needed?
        if (ocspreq.isSigned()) {
        }*/
        BasicOCSPRespBuilder respBuilder = Revocation.initOCSPRespBuilder(ocspreq, certUtil.getKeystoreHandler().getMCCertificate(certAlias).getPublicKey());
        Req[] requests = ocspreq.getRequestList();
        for (Req req : requests) {
            BigInteger sn = req.getCertID().getSerialNumber();
            Certificate cert = this.certificateService.getCertificateBySerialNumber(sn);

            if (cert == null) {
                respBuilder.addResponse(req.getCertID(), new UnknownStatus());

            // Check if the certificate is even signed by this CA
            } else if (!certAlias.equals(cert.getCertificateAuthority())) {
                respBuilder.addResponse(req.getCertID(), new UnknownStatus());

            // Check if certificate has been revoked
            } else if (cert.isRevoked()) {
                respBuilder.addResponse(req.getCertID(), new RevokedStatus(cert.getRevokedAt(), Revocation.getCRLReasonFromString(cert.getRevokeReason())));

            } else {
                // Certificate is valid
                respBuilder.addResponse(req.getCertID(), CertificateStatus.GOOD);
            }
        }
        OCSPResp response = Revocation.generateOCSPResponse(respBuilder, certUtil.getKeystoreHandler().getSigningCertEntry(certAlias));
        return response.getEncoded();
    }
}
