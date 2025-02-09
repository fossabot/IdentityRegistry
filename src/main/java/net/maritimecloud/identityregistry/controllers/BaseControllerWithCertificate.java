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

import net.maritimecloud.identityregistry.exception.McBasicRestException;
import net.maritimecloud.identityregistry.model.data.CertificateBundle;
import net.maritimecloud.identityregistry.model.data.CertificateRevocation;
import net.maritimecloud.identityregistry.model.data.PemCertificate;
import net.maritimecloud.identityregistry.model.database.Certificate;
import net.maritimecloud.identityregistry.model.database.CertificateModel;
import net.maritimecloud.identityregistry.model.database.Organization;
import net.maritimecloud.identityregistry.model.database.entities.EntityModel;
import net.maritimecloud.identityregistry.model.database.entities.NonHumanEntityModel;
import net.maritimecloud.identityregistry.services.CertificateService;
import net.maritimecloud.identityregistry.utils.CertificateUtil;
import net.maritimecloud.identityregistry.utils.MCIdRegConstants;
import net.maritimecloud.identityregistry.utils.PasswordUtil;
import net.maritimecloud.pki.CertificateBuilder;
import net.maritimecloud.pki.CertificateHandler;
import net.maritimecloud.pki.PKIConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;

@RestController
@RequestMapping(value={"oidc", "x509"})
public abstract class BaseControllerWithCertificate {

    @Autowired
    private CertificateService certificateService;

    @Autowired
    protected CertificateUtil certificateUtil;

    protected CertificateBundle issueCertificate(CertificateModel certOwner, Organization org, String type, HttpServletRequest request) throws McBasicRestException {
        // Generate keypair for user
        KeyPair userKeyPair = CertificateBuilder.generateKeyPair();
        // Find special MC attributes to put in the certificate
        HashMap<String, String> attrs = getAttr(certOwner);

        String o = org.getMrn();
        String name = getName(certOwner);
        String email = getEmail(certOwner);
        String uid = getUid(certOwner);
        if (uid == null || uid.trim().isEmpty()) {
            throw new McBasicRestException(HttpStatus.BAD_REQUEST, MCIdRegConstants.ENTITY_ORG_ID_MISSING, request.getServletPath());
        }
        BigInteger serialNumber = certificateUtil.getCertificateBuilder().generateSerialNumber();
        X509Certificate userCert;
        try {
            userCert = certificateUtil.getCertificateBuilder().generateCertForEntity(serialNumber, org.getCountry(), o, type, name, email, uid, userKeyPair.getPublic(), attrs, org.getCertificateAuthority(), certificateUtil.getBaseCrlOcspCrlURI());
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        String pemCertificate;
        try {
            pemCertificate = CertificateHandler.getPemFromEncoded("CERTIFICATE", userCert.getEncoded()).replace("\n", "\\n");
        } catch (CertificateEncodingException e) {
           throw new RuntimeException(e.getMessage(), e);
        }
        String pemPublicKey = CertificateHandler.getPemFromEncoded("PUBLIC KEY", userKeyPair.getPublic().getEncoded()).replace("\n", "\\n");
        String pemPrivateKey = CertificateHandler.getPemFromEncoded("PRIVATE KEY", userKeyPair.getPrivate().getEncoded()).replace("\n", "\\n");
        PemCertificate ret = new PemCertificate(pemPrivateKey, pemPublicKey, pemCertificate);

        // create the JKS and PKCS12 keystores and pack them in a bundle with the PEM certificate
        String keystorePassword = PasswordUtil.generatePassword();
        byte[] jksKeystore = CertificateHandler.createOutputKeystore("JKS", name, keystorePassword, userKeyPair.getPrivate(), userCert);
        byte[] pkcs12Keystore = CertificateHandler.createOutputKeystore("PKCS12", name, keystorePassword, userKeyPair.getPrivate(), userCert);
        Base64.Encoder encoder = Base64.getEncoder();
        CertificateBundle certificateBundle = new CertificateBundle(ret, new String(encoder.encode(jksKeystore), StandardCharsets.UTF_8), new String(encoder.encode(pkcs12Keystore), StandardCharsets.UTF_8), keystorePassword);

        // Create the certificate
        Certificate newMCCert = new Certificate();
        certOwner.assignToCert(newMCCert);
        newMCCert.setCertificate(pemCertificate);
        newMCCert.setSerialNumber(serialNumber);
        newMCCert.setCertificateAuthority(org.getCertificateAuthority());
        // The dates we extract from the cert is in localtime, so they are converted to UTC before saving into the DB
        Calendar cal = Calendar.getInstance();
        int offset = cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET);
        newMCCert.setStart(new Date(userCert.getNotBefore().getTime() - offset));
        newMCCert.setEnd(new Date(userCert.getNotAfter().getTime() - offset));
        this.certificateService.saveCertificate(newMCCert);
        return certificateBundle;
    }

    protected void revokeCertificate(BigInteger certId, CertificateRevocation input, HttpServletRequest request) throws McBasicRestException {
        Certificate cert = this.certificateService.getCertificateBySerialNumber(certId);
        if (!input.validateReason()) {
            throw new McBasicRestException(HttpStatus.BAD_REQUEST, MCIdRegConstants.INVALID_REVOCATION_REASON, request.getServletPath());
        }
        if (input.getRevokedAt() == null) {
            throw new McBasicRestException(HttpStatus.BAD_REQUEST, MCIdRegConstants.INVALID_REVOCATION_DATE, request.getServletPath());
        }
        cert.setRevokedAt(input.getRevokedAt());
        cert.setRevokeReason(input.getRevokationReason());
        cert.setRevoked(true);
        this.certificateService.saveCertificate(cert);
    }

    /* Override if the entity type of the controller isn't of type NonHumanEntityModel */
    protected String getName(CertificateModel certOwner) {
        return ((NonHumanEntityModel)certOwner).getName();
    }

    /* Override if the entity type of the controller isn't of type NonHumanEntityModel */
    protected abstract String getUid(CertificateModel certOwner);

    /* Override if the entity type of the controller has an email */
    protected String getEmail(CertificateModel certOwner) {
        return "";
    }

    /* Override if the entity type isn't of type EntityModel */
    protected HashMap<String, String> getAttr(CertificateModel certOwner) {
        HashMap<String, String> attrs = new HashMap<>();
        EntityModel entity = (EntityModel) certOwner;
        if (entity.getMrn() != null) {
            attrs.put(PKIConstants.MC_OID_MRN, entity.getMrn());
        }
        if (entity.getPermissions() != null) {
            attrs.put(PKIConstants.MC_OID_PERMISSIONS, entity.getPermissions());
        }
        return attrs;
    }
}
