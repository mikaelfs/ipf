/*
 * Copyright 2009 the original author or authors.
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
package org.openehealth.ipf.platform.camel.ihe.xds.iti14.audit;

import org.openehealth.ipf.platform.camel.ihe.xds.commons.cxf.audit.AuditDataset;
import org.openehealth.ipf.platform.camel.ihe.xds.commons.cxf.audit.AuditStrategy;
import org.openehealth.ipf.platform.camel.ihe.xds.commons.stub.ebrs21.rs.SubmitObjectsRequest;
import org.openehealth.ipf.platform.camel.ihe.xds.commons.utils.Ebxml21Utils;

/**
 * Audit strategy for ITI-14.
 * 
 * @author Dmytro Rud
 */
abstract public class Iti14AuditStrategy implements AuditStrategy {

    @Override
    public void enrichDataset(Object pojo, AuditDataset genericAuditDataset) {
        SubmitObjectsRequest request = (SubmitObjectsRequest) pojo;
        Ebxml21Utils.enrichDatasetFromSubmitObjectsRequest(request, genericAuditDataset);
    }

    @Override
    public boolean needSavePayload() {
        return false;
    }
}