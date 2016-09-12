/* Copyright 2016 Danish Maritime Authority.
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
package net.maritimecloud.identityregistry.services;

import net.maritimecloud.identityregistry.model.database.Organization;

import java.util.List;

public interface OrganizationService extends BaseService<Organization>{
    Organization getOrganizationByShortName(String shortname);

    Organization getOrganizationByShortNameDisregardApproved(String shortname);
    /* Does not filter sensitive data from the result! */
    Organization getOrganizationByShortNameNoFilter(String shortname);

    List<Organization> getUnapprovedOrganizations();
}