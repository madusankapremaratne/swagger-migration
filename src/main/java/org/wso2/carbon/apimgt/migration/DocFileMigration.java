package org.wso2.carbon.apimgt.migration;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.Documentation;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.migration.internal.ServiceHolder;
import org.wso2.carbon.base.MultitenantConstants;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.governance.api.common.dataobjects.GovernanceArtifact;
import org.wso2.carbon.governance.api.exception.GovernanceException;
import org.wso2.carbon.governance.api.generic.GenericArtifactManager;
import org.wso2.carbon.governance.api.generic.dataobjects.GenericArtifact;
import org.wso2.carbon.governance.api.util.GovernanceUtils;
import org.wso2.carbon.registry.core.Association;
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.RegistryConstants;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.registry.core.session.UserRegistry;
import org.wso2.carbon.user.api.Tenant;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.tenant.TenantManager;

import java.sql.SQLException;
import java.util.Arrays;

public class DocFileMigration {

    private static final Log log = LogFactory.getLog(DocFileMigration.class);

    public void migrate() throws UserStoreException {
        log.info("*** In migrate() of DocFileMigration ***");

        TenantManager tenantManager = ServiceHolder.getRealmService().getTenantManager();
        Tenant[] tenantsArray = tenantManager.getAllTenants();

        // Add  super tenant to the tenant array
        Tenant[] allTenantsArray = Arrays.copyOf(tenantsArray, tenantsArray.length + 1);
        org.wso2.carbon.user.core.tenant.Tenant superTenant = new org.wso2.carbon.user.core.tenant.Tenant();
        superTenant.setId(MultitenantConstants.SUPER_TENANT_ID);
        superTenant.setDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
        allTenantsArray[allTenantsArray.length - 1] = superTenant;

        for (Tenant tenant : allTenantsArray) {
            log.info("*** Document file migration for tenant " + tenant.getDomain() + "[" + tenant.getId() + "]" + " ***");
            try {
                //Start a new tenant flow
                PrivilegedCarbonContext.getThreadLocalCarbonContext().startTenantFlow();
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenant.getDomain());
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantId(tenant.getId());

                String adminName = ServiceHolder.getRealmService().getTenantUserRealm(tenant.getId()).getRealmConfiguration().getAdminUserName();
                ServiceHolder.getTenantRegLoader().loadTenantRegistry(tenant.getId());
                Registry registry = ServiceHolder.getRegistryService().getGovernanceUserRegistry(adminName, tenant.getId());
                GenericArtifactManager manager = new GenericArtifactManager(registry, "api");
                GovernanceUtils.loadGovernanceArtifacts((UserRegistry) registry);
                GenericArtifact[] artifacts = manager.getAllGenericArtifacts();

                for (GenericArtifact artifact : artifacts) {
                    try {
                        API api = getAPI(artifact, registry);
                        APIIdentifier apiIdentfier = api.getId();
                        String apiResourcePath = APIUtil.getAPIPath(apiIdentfier);
                        Association[] docAssociations = registry.getAssociations(apiResourcePath, "document");
                        for (Association association : docAssociations) {
                            String docPath = association.getDestinationPath();
                            Resource docResource = registry.get(docPath);
                            GenericArtifactManager docArtifactManager = new GenericArtifactManager(registry, APIConstants.DOCUMENTATION_KEY);
                            GenericArtifact docArtifact = docArtifactManager.getGenericArtifact(docResource.getUUID());
                            String docFilePath = docArtifact.getAttribute(APIConstants.DOC_FILE_PATH);
                            Documentation doc = APIUtil.getDocumentation(docArtifact);
                            if (Documentation.DocumentSourceType.FILE.equals(doc.getSourceType())) {
                                if (docFilePath != null && !docFilePath.equals("")) {
                                    //The docFilePatch comes as /t/tenanatdoman/registry/resource/_system/governance/apimgt/applicationdata..
                                    //We need to remove the /t/tenanatdoman/registry/resource/_system/governance section to set permissions.
                                    int startIndex = docFilePath.indexOf("governance") + "governance".length();
                                    String filePath = docFilePath.substring(startIndex, docFilePath.length());
                                    if (filePath.split(RegistryConstants.PATH_SEPARATOR + "files" + RegistryConstants.PATH_SEPARATOR)[1].indexOf(".") == -1) {
                                        Resource resource = registry.get(filePath);
                                        resource.setMediaType("text/plain");
                                        registry.put(filePath, resource);
                                    } else if (filePath.split(RegistryConstants.PATH_SEPARATOR + "files" + RegistryConstants.PATH_SEPARATOR)[1].indexOf(".wsdl") > -1) {
                                        String resourcePath = "_system" + RegistryConstants.PATH_SEPARATOR + "governance" + filePath;
                                        if (registry.resourceExists(resourcePath)) {
                                            Resource resource = registry.get(filePath);
                                            resource.setMediaType("application/api-wsdl");
                                            registry.put(filePath, resource);
                                        }
                                    } else if (filePath.split(RegistryConstants.PATH_SEPARATOR + "files" + RegistryConstants.PATH_SEPARATOR)[1].indexOf(".pdf") > -1) {
                                        String resourcePath = "_system" + RegistryConstants.PATH_SEPARATOR + "governance" + filePath;
                                        if (registry.resourceExists(resourcePath)) {
                                            Resource resource = registry.get(filePath);
                                            resource.setMediaType("application/pdf");
                                            registry.put(filePath, resource);
                                        }
                                    } else if (filePath.split(RegistryConstants.PATH_SEPARATOR + "files" + RegistryConstants.PATH_SEPARATOR)[1].indexOf(".xl") > -1) {
                                        String resourcePath = "_system" + RegistryConstants.PATH_SEPARATOR + "governance" + filePath;
                                        if (registry.resourceExists(resourcePath)) {
                                            Resource resource = registry.get(filePath);
                                            resource.setMediaType("application/vnd.ms-excel");
                                            registry.put(filePath, resource);
                                        }
                                    } else if (filePath.split(RegistryConstants.PATH_SEPARATOR + "files" + RegistryConstants.PATH_SEPARATOR)[1].indexOf(".ppt") > -1) {
                                        String resourcePath = "_system" + RegistryConstants.PATH_SEPARATOR + "governance" + filePath;
                                        if (registry.resourceExists(resourcePath)) {
                                            Resource resource = registry.get(filePath);
                                            resource.setMediaType("application/vnd.ms-powerpoint");
                                            registry.put(filePath, resource);
                                        }
                                    } else if (filePath.split(RegistryConstants.PATH_SEPARATOR + "files" + RegistryConstants.PATH_SEPARATOR)[1].indexOf(".xml") > -1) {
                                        String resourcePath = "_system" + RegistryConstants.PATH_SEPARATOR + "governance" + filePath;
                                        if (registry.resourceExists(resourcePath)) {
                                            Resource resource = registry.get(filePath);
                                            resource.setMediaType("application/xml");
                                            registry.put(filePath, resource);
                                        }
                                    } else if (filePath.split(RegistryConstants.PATH_SEPARATOR + "files" + RegistryConstants.PATH_SEPARATOR)[1].indexOf(".js") > -1) {
                                        String resourcePath = "_system" + RegistryConstants.PATH_SEPARATOR + "governance" + filePath;
                                        if (registry.resourceExists(resourcePath)) {
                                            Resource resource = registry.get(filePath);
                                            resource.setMediaType("application/javascript");
                                            registry.put(filePath, resource);
                                        }
                                    } else if (filePath.split(RegistryConstants.PATH_SEPARATOR + "files" + RegistryConstants.PATH_SEPARATOR)[1].indexOf(".css") > -1) {
                                        String resourcePath = "_system" + RegistryConstants.PATH_SEPARATOR + "governance" + filePath;
                                        if (registry.resourceExists(resourcePath)) {
                                            Resource resource = registry.get(filePath);
                                            resource.setMediaType("text/css");
                                            registry.put(filePath, resource);
                                        }
                                    } else if (filePath.split(RegistryConstants.PATH_SEPARATOR + "files" + RegistryConstants.PATH_SEPARATOR)[1].indexOf(".csv") > -1) {
                                        String resourcePath = "_system" + RegistryConstants.PATH_SEPARATOR + "governance" + filePath;
                                        if (registry.resourceExists(resourcePath)) {
                                            Resource resource = registry.get(filePath);
                                            resource.setMediaType("text/csv");
                                            registry.put(filePath, resource);
                                        }
                                    } else if (filePath.split(RegistryConstants.PATH_SEPARATOR + "files" + RegistryConstants.PATH_SEPARATOR)[1].indexOf(".html") > -1) {
                                        String resourcePath = "_system" + RegistryConstants.PATH_SEPARATOR + "governance" + filePath;
                                        if (registry.resourceExists(resourcePath)) {
                                            Resource resource = registry.get(filePath);
                                            resource.setMediaType("text/html");
                                            registry.put(filePath, resource);
                                        }
                                    } else if (filePath.split(RegistryConstants.PATH_SEPARATOR + "files" + RegistryConstants.PATH_SEPARATOR)[1].indexOf(".json") > -1) {
                                        String resourcePath = "_system" + RegistryConstants.PATH_SEPARATOR + "governance" + filePath;
                                        if (registry.resourceExists(resourcePath)) {
                                            Resource resource = registry.get(filePath);
                                            resource.setMediaType("application/json");
                                            registry.put(filePath, resource);
                                        }
                                    } else if (filePath.split(RegistryConstants.PATH_SEPARATOR + "files" + RegistryConstants.PATH_SEPARATOR)[1].indexOf(".png") > -1) {
                                        String resourcePath = "_system" + RegistryConstants.PATH_SEPARATOR + "governance" + filePath;
                                        if (registry.resourceExists(resourcePath)) {
                                            Resource resource = registry.get(filePath);
                                            resource.setMediaType("image/png");
                                            registry.put(filePath, resource);
                                        }
                                    } else if (filePath.split(RegistryConstants.PATH_SEPARATOR + "files" + RegistryConstants.PATH_SEPARATOR)[1].indexOf(".ttf") > -1) {
                                        String resourcePath = "_system" + RegistryConstants.PATH_SEPARATOR + "governance" + filePath;
                                        if (registry.resourceExists(resourcePath)) {
                                            Resource resource = registry.get(filePath);
                                            resource.setMediaType("application/x-font-ttf");
                                            registry.put(filePath, resource);
                                        }
                                    } else if (filePath.split(RegistryConstants.PATH_SEPARATOR + "files" + RegistryConstants.PATH_SEPARATOR)[1].indexOf(".eot") > -1) {
                                        String resourcePath = "_system" + RegistryConstants.PATH_SEPARATOR + "governance" + filePath;
                                        if (registry.resourceExists(resourcePath)) {
                                            Resource resource = registry.get(filePath);
                                            resource.setMediaType("application/vnd.ms-fontobject");
                                            registry.put(filePath, resource);
                                        }
                                    } else if (filePath.split(RegistryConstants.PATH_SEPARATOR + "files" + RegistryConstants.PATH_SEPARATOR)[1].indexOf(".woff") > -1) {
                                        String resourcePath = "_system" + RegistryConstants.PATH_SEPARATOR + "governance" + filePath;
                                        if (registry.resourceExists(resourcePath)) {
                                            Resource resource = registry.get(filePath);
                                            resource.setMediaType("application/font-woff");
                                            registry.put(filePath, resource);
                                        }
                                    } else if (filePath.split(RegistryConstants.PATH_SEPARATOR + "files" + RegistryConstants.PATH_SEPARATOR)[1].indexOf(".otf") > -1) {
                                        String resourcePath = "_system" + RegistryConstants.PATH_SEPARATOR + "governance" + filePath;
                                        if (registry.resourceExists(resourcePath)) {
                                            Resource resource = registry.get(filePath);
                                            resource.setMediaType("application/x-font-otf");
                                            registry.put(filePath, resource);
                                        }
                                    } else if (filePath.split(RegistryConstants.PATH_SEPARATOR + "files" + RegistryConstants.PATH_SEPARATOR)[1].indexOf(".zip") > -1) {
                                        String resourcePath = "_system" + RegistryConstants.PATH_SEPARATOR + "governance" + filePath;
                                        if (registry.resourceExists(resourcePath)) {
                                            Resource resource = registry.get(filePath);
                                            resource.setMediaType("application/zip");
                                            registry.put(filePath, resource);
                                        }
                                    } else if (filePath.split(RegistryConstants.PATH_SEPARATOR + "files" + RegistryConstants.PATH_SEPARATOR)[1].indexOf(".xhtml") > -1) {
                                        String resourcePath = "_system" + RegistryConstants.PATH_SEPARATOR + "governance" + filePath;
                                        if (registry.resourceExists(resourcePath)) {
                                            Resource resource = registry.get(filePath);
                                            resource.setMediaType("application/xhtml+xml");
                                            registry.put(filePath, resource);
                                        }
                                    } else if (filePath.split(RegistryConstants.PATH_SEPARATOR + "files" + RegistryConstants.PATH_SEPARATOR)[1].indexOf(".txt") > -1) {
                                        String resourcePath = "_system" + RegistryConstants.PATH_SEPARATOR + "governance" + filePath;
                                        if (registry.resourceExists(resourcePath)) {
                                            Resource resource = registry.get(filePath);
                                            resource.setMediaType("text/plain");
                                            registry.put(filePath, resource);
                                        }
                                    } else if (filePath.split(RegistryConstants.PATH_SEPARATOR + "files" + RegistryConstants.PATH_SEPARATOR)[1].indexOf(".png") > -1) {
                                        String resourcePath = "_system" + RegistryConstants.PATH_SEPARATOR + "governance" + filePath;
                                        if (registry.resourceExists(resourcePath)) {
                                            Resource resource = registry.get(filePath);
                                            resource.setMediaType("image/png");
                                            registry.put(filePath, resource);
                                        }
                                    } else if (filePath.split(RegistryConstants.PATH_SEPARATOR + "files" + RegistryConstants.PATH_SEPARATOR)[1].indexOf(".jpeg") > -1) {
                                        String resourcePath = "_system" + RegistryConstants.PATH_SEPARATOR + "governance" + filePath;
                                        if (registry.resourceExists(resourcePath)) {
                                            Resource resource = registry.get(filePath);
                                            resource.setMediaType("image/jpeg");
                                            registry.put(filePath, resource);
                                        }
                                    }
                                    registry.copy(filePath, filePath);
                                    registry.addAssociation(docArtifact.getPath(), filePath, APIConstants.DOCUMENTATION_FILE_ASSOCIATION);
                                }
                            }
                        }
                    } catch (APIManagementException e) {
                        log.error(e.getMessage(), e);
                    } catch (SQLException e) {
                        log.error(e.getMessage(), e);
                    } catch (RegistryException e) {
                        log.error(e.getMessage(), e);
                    } catch (Exception e) {
                        log.error(e.getMessage(), e);
                    }
                }
            } catch (RegistryException e) {
                log.error(e.getMessage(), e);
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            } finally {
                PrivilegedCarbonContext.getThreadLocalCarbonContext().endTenantFlow();
            }
        }
    }

    public static API getAPI(GovernanceArtifact artifact, Registry registry)
            throws APIManagementException, SQLException {

        API api;
        try {
            String providerName = artifact.getAttribute(APIConstants.API_OVERVIEW_PROVIDER);
            String apiName = artifact.getAttribute(APIConstants.API_OVERVIEW_NAME);
            String apiVersion = artifact.getAttribute(APIConstants.API_OVERVIEW_VERSION);
            APIIdentifier apiId = new APIIdentifier(providerName, apiName, apiVersion);
            api = new API(apiId);

            api.setUrl(artifact.getAttribute(APIConstants.API_OVERVIEW_ENDPOINT_URL));
            api.setSandboxUrl(artifact.getAttribute(APIConstants.API_OVERVIEW_SANDBOX_URL));
            api.setVisibility(artifact.getAttribute(APIConstants.API_OVERVIEW_VISIBILITY));
            api.setVisibleRoles(artifact.getAttribute(APIConstants.API_OVERVIEW_VISIBLE_ROLES));


        } catch (GovernanceException e) {
            String msg = "Failed to get API fro artifact ";
            throw new APIManagementException(msg, e);
        }
        return api;
    }


}