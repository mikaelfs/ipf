/*
 * Copyright 2008 the original author or authors.
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
package org.openehealth.ipf.commons.lbs.resource;

import static org.apache.commons.lang.Validate.notNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Observer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openehealth.ipf.commons.lbs.store.LargeBinaryStore;

/**
 * Factory component for resources that are generated by processors or 
 * endpoints and require storage and life-cycle management.
 * <p>
 * This factory provides storage via an attached {@link LargeBinaryStore}.
 * Creation and deletion of resources can be observed by {@link Observer}s.
 * @author Jens Riemschneider
 */
public class ResourceFactory {
    private final String defaultResourceId;    
    private final LargeBinaryStore store;
    private final Map<String, List<ResourceDataSource>> unitOfWorkMap;
    
    private static final Log log = LogFactory.getLog(ResourceFactory.class);

    /**
     * Constructs the factory via the store.
     * @param store
     *          the storage that is used for resources
     * @param defaultResourceId
     *          default id that is used for resources that do not get their
     *          id defined via other sources
     */
    public ResourceFactory(LargeBinaryStore store, String defaultResourceId) {        
        notNull(store, "store cannot be null");
        notNull(defaultResourceId, "defaultResourceId cannot be null");
        
        this.store = store;
        this.defaultResourceId = defaultResourceId;

        unitOfWorkMap = new HashMap<String, List<ResourceDataSource>>();
        
        log.debug("created: " + this);
    }
    
    /**
     * Creates a resource with initial content
     * @param unitOfWorkId
     *          the id of the unit of work that the resource is created for
     * @param contentType
     *          type of the content contained in the input stream
     * @param name
     *          name of the resource. May be {@code null} if the content is
     *          unnamed.
     * @param id
     *          id of the resource. May be {@code null} if the id is not 
     *          known and the default id should be used.
     * @param inputStream
     *          stream to the actual content
     * @return the created resource
     */
    public ResourceDataSource createResource(String unitOfWorkId, String contentType, String name, String id, InputStream inputStream) {
        notNull(contentType, "contentType cannot be null");
        notNull(inputStream, "inputStream cannot be null");
        notNull(unitOfWorkId, "unitOfWorkId cannot be null");
        URI resourceUri = store.add(inputStream);       
        return createResource(unitOfWorkId, contentType, name, id, resourceUri);
    }

    /**
     * Creates a resource without any initial content.
     * <p>
     * Note: It is expected that the caller fills the content after 
     * receiving the data source via the stream returned by 
     * {@link ResourceDataSource#getOutputStream()}.
     * @param unitOfWorkId
     *          the id of the unit of work that the resource is created for
     * @param contentType
     *          type of the content that will be written to the resource
     * @param name
     *          name of the resource. May be {@code null} if the content is
     *          unnamed.
     * @param id
     *          id of the resource. May be {@code null} if the id is not known 
     *          and the default id should be used.
     * @return the created resource
     */
    public ResourceDataSource createResource(String unitOfWorkId, String contentType, String name, String id) {
        notNull(contentType, "contentType cannot be null");
        notNull(unitOfWorkId, "unitOfWorkId cannot be null");
        URI resourceUri = store.add();
        return createResource(unitOfWorkId, contentType, name, id, resourceUri);
    }
    
    /**
     * Creates a resource without any initial content.
     * <p>
     * The content type is set to "application/unknown".
     * @param unitOfWorkId
     *          the id of the unit of work that the resource is created for
     * @return the created resource
     * @throws IOException
     *          if a problem occurred related to the input stream in the
     *          resource description
     */
    public ResourceDataSource createResource(String unitOfWorkId) throws IOException {
        notNull(unitOfWorkId, "unitOfWorkId cannot be null");
        return createResource(unitOfWorkId, "application/unknown", null, null);
    }

    /**
     * Deletes a resource
     * @param unitOfWorkId
     *          the id of the unit of work that the resource is created for
     * @param resource
     *          the resource that is deleted 
     */
    public void deleteResource(String unitOfWorkId, ResourceDataSource resource) {
        notNull(resource, "resource cannot be null");
        notNull(unitOfWorkId, "unitOfWorkId cannot be null");
        
        resource.delete();
        unregisterFromUnitOfWork(unitOfWorkId, resource);

        log.debug("deleted resource: " + resource);
    }    
    
    /**
     * Deletes a resource after the next usage
     * @param unitOfWorkId
     *          the id of the unit of work that the resource is created for
     * @param resource
     *          the resource that is deleted 
     */
    public void deleteResourceDelayed(String unitOfWorkId, ResourceDataSource resource) {
        notNull(resource, "resource cannot be null");
        notNull(unitOfWorkId, "unitOfWorkId cannot be null");
        
        resource.deleteAfterNextUsage();
        unregisterFromUnitOfWork(unitOfWorkId, resource);

        log.debug("marked resource for delayed deletion: " + resource);
    }

    /**
     * Returns all resources that were created for the given unit of work
     * @param unitOfWorkId
     *          the id of the unit of work that the resource is created for
     * @return the list of resources. Never <code>null</code>
     */
    public List<ResourceDataSource> getResources(String unitOfWorkId) {
        notNull(unitOfWorkId, "unitOfWorkId cannot be null");
        return getRegisteredResources(unitOfWorkId);
    }    
    
    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return String.format("{%1$s: defaultResourceId=%2$s, store=%3$s}",
                getClass().getSimpleName(), defaultResourceId, store);
    }
    
    private ResourceDataSource createResource(String unitOfWorkId, String contentType, String name, String id, URI resourceUri) {
        LargeBinaryStoreDataSource dataSource = 
            new LargeBinaryStoreDataSource(resourceUri, contentType, name);
        
        ResourceDataSource resource = new ResourceDataSource(safeResourceId(id), dataSource);
        
        registerWithUnitOfWork(unitOfWorkId, resource);
         
        log.debug("created resource: " + resource);

        return resource;
    }
    
    private List<ResourceDataSource> getRegisteredResources(String unitOfWorkId) {
        synchronized (unitOfWorkMap) {
            List<ResourceDataSource> registeredResources = unitOfWorkMap.get(unitOfWorkId);
            if (registeredResources == null) {
                return Collections.emptyList();
            }
            
            return new ArrayList<ResourceDataSource>(registeredResources);
        }
    }

    private void unregisterFromUnitOfWork(String unitOfWorkId, ResourceDataSource resource) {
        synchronized (unitOfWorkMap) {
            List<ResourceDataSource> registeredResources = unitOfWorkMap.get(unitOfWorkId);
            if (registeredResources != null) {
                registeredResources.remove(resource);
            }
        }
    }

    private void registerWithUnitOfWork(String unitOfWorkId, ResourceDataSource resource) {
        synchronized (unitOfWorkMap) {
            List<ResourceDataSource> registeredResources = unitOfWorkMap.get(unitOfWorkId);
            if (registeredResources == null) {
                registeredResources = new ArrayList<ResourceDataSource>();
                unitOfWorkMap.put(unitOfWorkId, registeredResources);
            }
            registeredResources.add(resource);
        }
    }

    private String safeResourceId(String id) {
        return id != null ? id : defaultResourceId;
    }
}
