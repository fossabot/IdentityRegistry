/*
 * Copyright 2018 Danish Maritime Authority
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.maritimecloud.identityregistry.model.database;

import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;
import net.maritimecloud.identityregistry.validators.InPredefinedList;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import java.util.Objects;

@Entity
@Table(name = "acting_on_behalf")
@Getter
@Setter
public class Agent extends TimestampModel {

    /**
     * The organization that is acting on behalf of the other organization
     */
    @ApiModelProperty(required = true, value = "The id of the organization that should be given agent permissions")
    @Column(name = "id_acting", nullable = false)
    private Long idActingOrganization;

    /**
     * The organization that is being acted on behalf of
     */
    @ApiModelProperty(value = "The id of the organization that is giving agent permissions")
    @Column(name = "id_on_behalf_of", nullable = false)
    private Long idOnBehalfOfOrganization;

    /**
     * The role that the acting organization is allowed to perform
     */
    @ApiModelProperty(required = true, value = "The role that should be given to the agent")
    @Column(name = "role_name", nullable = false)
    @InPredefinedList(acceptedValues = {"ROLE_SITE_ADMIN", "ROLE_ORG_ADMIN", "ROLE_ENTITY_ADMIN", "ROLE_USER_ADMIN",
            "ROLE_VESSEL_ADMIN", "ROLE_SERVICE_ADMIN", "ROLE_DEVICE_ADMIN", "ROLE_USER", "ROLE_APPROVE_ORG"})
    private String roleName;

    /** Copies this agent into the other */
    public Agent copyTo(Agent agent) {
        Objects.requireNonNull(agent);
        agent.setIdActingOrganization(this.idActingOrganization);
        agent.setIdOnBehalfOfOrganization(this.idOnBehalfOfOrganization);
        agent.setRoleName(this.roleName);
        agent.setId(this.id);
        return agent;
    }
}
