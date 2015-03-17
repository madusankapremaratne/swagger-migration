/*
 * Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 * 
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.apimgt.migration;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.URITemplate;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.migration.internal.ServiceHolder;
import org.wso2.carbon.apimgt.migration.util.Constants;
import org.wso2.carbon.apimgt.migration.util.ResourceUtil;
import org.wso2.carbon.base.MultitenantConstants;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.governance.api.common.dataobjects.GovernanceArtifact;
import org.wso2.carbon.governance.api.exception.GovernanceException;
import org.wso2.carbon.governance.api.generic.GenericArtifactManager;
import org.wso2.carbon.governance.api.generic.dataobjects.GenericArtifact;
import org.wso2.carbon.governance.api.util.GovernanceUtils;
import org.wso2.carbon.registry.core.ActionConstants;
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.registry.core.session.UserRegistry;
import org.wso2.carbon.user.api.Tenant;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.tenant.TenantManager;

import java.net.MalformedURLException;
import java.net.URL;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Class responsible to migrate swagger v1.2 documents to swagger v2.0 document and set it to a 
 * new swagger v2.0 location.  Swagger v2.0 doc is generated according to the <a href="https://github.com/swagger-api/swagger-spec/blob/master/versions/2.0.md">Swagger 2 Specification</a>
 */
public class Swagger19Migration {
    private static final Log log = LogFactory.getLog(Swagger19Migration.class);


    public void migrate() throws UserStoreException, InterruptedException {
        log.info("Swagger migration for AM 1.9 started");

        TenantManager tenantManager = ServiceHolder.getRealmService().getTenantManager();
        Tenant[] tenantsArray = tenantManager.getAllTenants();

        // Add  super tenant to the tenant array
        Tenant[] allTenantsArray = Arrays.copyOf(tenantsArray, tenantsArray.length + 1);
        org.wso2.carbon.user.core.tenant.Tenant superTenant = new org.wso2.carbon.user.core.tenant.Tenant();
        superTenant.setId(MultitenantConstants.SUPER_TENANT_ID);
        superTenant.setDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
        allTenantsArray[allTenantsArray.length - 1] = superTenant;

        for (Tenant tenant : allTenantsArray) {
            log.info("Swagger migration for tenant " + tenant.getDomain() + "[" + tenant.getId() + "]" + " ");
            try {
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenant.getDomain());
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantId(tenant.getId());

                String adminName = ServiceHolder.getRealmService().getTenantUserRealm(
                		tenant.getId()).getRealmConfiguration().getAdminUserName();
                ServiceHolder.getTenantRegLoader().loadTenantRegistry(tenant.getId());
                Registry registry = ServiceHolder.getRegistryService().getGovernanceUserRegistry(adminName, tenant.getId());
                GenericArtifactManager manager = new GenericArtifactManager(registry, "api");
                GovernanceUtils.loadGovernanceArtifacts((UserRegistry) registry);
                GenericArtifact[] artifacts = manager.getAllGenericArtifacts();

                for (GenericArtifact artifact : artifacts) {
                    API api;
					try {
						api = getAPI(artifact, registry);
					
	                    //API api = APIUtil.getAPI(artifact);
	                    APIIdentifier apiIdentfier = api.getId();
	
	            
	                    String swagger12location = ResourceUtil.getSwagger12ResourceLocation(
	                    										apiIdentfier.getApiName(),
	                                                            apiIdentfier.getVersion(),
	                                                            apiIdentfier.getProviderName());
	
	                        
	                    
	                    if(!registry.resourceExists(swagger12location)) {
	                        log.error("Swagger Resource migration has not happen yet for " +
	                        		apiIdentfier.getApiName() + "-" + apiIdentfier.getVersion() + "-" 
	                        		+ apiIdentfier.getProviderName() + 
	                        		". Please run -D" + Constants.VERSION_1_6 + " first");
	                        
	                    } else {
	                    	log.info("Creating swagger v2.0 resource for : " + apiIdentfier.getApiName() + "-" +
		        					apiIdentfier.getVersion() + "-" + apiIdentfier.getProviderName());   
	                    	//get swagger v2 doc
	                    	String swagger2doc = getSwagger2docUsingSwagger12RegistryResouces(registry, swagger12location);
	                    	
	                    	
	                    	//create location in registry and add this
	                    	String swagger2location = ResourceUtil.getSwagger2ResourceLocation(
																apiIdentfier.getApiName(),
							                                    apiIdentfier.getVersion(),
							                                    apiIdentfier.getProviderName());
	                    	
	                    	Resource docContent = registry.newResource();
		                    docContent.setContent(swagger2doc);
		                    docContent.setMediaType("application/json");
		                    registry.put(swagger2location, docContent);
		                    String visibleRolesList = api.getVisibleRoles();
		                    String[] visibleRoles = new String[0];
		                    if (visibleRolesList != null) {
		                        visibleRoles = visibleRolesList.split(",");
		                    }
		                    ServiceHolder.getRealmService().getTenantUserRealm(
		                    		tenant.getId()).getAuthorizationManager().authorizeRole(APIConstants.ANONYMOUS_ROLE,
		                    		"_system/governance" + swagger2location,
		                            ActionConstants.GET);
		                    log.info("Created swagger v2.0 resource for : " + apiIdentfier.getApiName() + "-" +
		        					apiIdentfier.getVersion() + "-" + apiIdentfier.getProviderName());   
		                    
	                    } 
	                    
	                    
					} catch (APIManagementException e) {
						log.error("APIManagementException while migrating api in " + tenant.getDomain() , e);	
					} catch (SQLException e) {
						log.error("SQL exception for " + tenant.getDomain(), e);
					} catch (RegistryException e) {
						log.error("RegistryException while getting api resource for " + tenant.getDomain() , e);
					} catch (ParseException e) {
						log.error("Error while parsing json resource for " + tenant.getDomain() , e);
					} catch (Exception e) {
                        log.error(e.getMessage(), e);
                    }
                }  
            } catch (RegistryException e) {
            	log.error("RegistryException while getting artifacts for  " + tenant.getDomain() , e);
			} catch (Exception e) {
                log.error(e.getMessage(), e);
            } finally {
                PrivilegedCarbonContext.endTenantFlow();
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
            api.setContext(artifact.getAttribute(APIConstants.API_OVERVIEW_CONTEXT));
            api.setDescription(artifact.getAttribute(APIConstants.API_OVERVIEW_DESCRIPTION));

            ArrayList<URITemplate> urlPatternsList = ApiMgtDAO.getAllURITemplates(api.getContext(), api.getId().getVersion());
            Set<URITemplate> uriTemplates = new HashSet<URITemplate>(urlPatternsList);
            for (URITemplate uriTemplate : uriTemplates) {
                uriTemplate.setResourceURI(api.getUrl());
                uriTemplate.setResourceSandboxURI(api.getSandboxUrl());
            }
            api.setUriTemplates(uriTemplates);


        } catch (GovernanceException e) {
            String msg = "Failed to get API fro artifact ";
            throw new APIManagementException(msg, e);
        }
        return api;
    }


    private String getSwagger2docUsingSwagger12RegistryResouces(Registry registry, String swagger12location)
    		throws MalformedURLException, ParseException, RegistryException {

		JSONParser parser = new JSONParser();		
    	String swagger12basepath = null;
    	
    	Resource swaggerRes = registry.get(swagger12location + APIConstants.API_DOC_1_2_RESOURCE_NAME );
		JSONObject swagger12doc = (JSONObject) parser.parse(new String((byte[]) swaggerRes.getContent()));
		
		Map<String, JSONArray> apiDefsPaths = new HashMap<String, JSONArray>();
		
		Resource swagger12Res = registry.get(swagger12location);
		//get all the resources inside the 1.2 resource location
        String[] apiDefs = (String[]) swagger12Res.getContent();
	
		//get each resource in the 1.2 folder except the api-doc resource
		for (int i = 0; i < apiDefs.length; i++) {
			
			String resourceName = apiDefs[i].substring(apiDefs[i].lastIndexOf("/"));
			//skip if api-doc file
			if (resourceName.equals(APIConstants.API_DOC_1_2_RESOURCE_NAME)) {				
				continue;
			}
	
			Resource resource = registry.get(apiDefs[i]);
			JSONObject apidef =
					(JSONObject) parser.parse(new String((byte[]) resource.getContent()));
			//get the basepath. this is same for all api definitions. 
			swagger12basepath = (String) apidef.get("basePath");
			JSONArray apis = (JSONArray)apidef.get("apis");
			for(int j = 0; j < apis.size(); j ++ ) {
				JSONObject api = (JSONObject) apis.get(j);
				String path =  (String) api.get("path");
				JSONArray operations = (JSONArray) api.get("operations");
				//set the operations object inside each api definitions resource and set it 
				//in a map againts its resource path
				apiDefsPaths.put(path, operations);				
			}	
			
		}
		
		
		JSONObject swagger2Doc = generateSwagger2Document(swagger12doc, apiDefsPaths, swagger12basepath);
		
		return swagger2Doc.toJSONString();
	}


	/**
	 * Generate Swagger v2.0 document using Swagger v1.2 resources
	 * @param swagger12doc
	 * @param apiDefsPaths
	 * @param swagger12basepath
	 * @return Swagger v2.0 document as a JSON object
	 * @throws ParseException
	 * @throws MalformedURLException
	 */
	private static JSONObject generateSwagger2Document(JSONObject swagger12doc,
			Map<String, JSONArray> apiDefsPaths, String swagger12basepath) throws ParseException, MalformedURLException {
		//create swagger 2.0 doc
		JSONObject swagger20doc = new JSONObject();

		//set swagger version
	    swagger20doc.put("swagger", "2.0");
		
		//set the info object
		JSONObject info =  generateInfoObject(swagger12doc);
		//update info object		
		swagger20doc.put("info", info);
		
		//set the paths object
		JSONObject pathObj = generatePathObj(apiDefsPaths);
		swagger20doc.put("paths", pathObj);
		
		URL url = new URL(swagger12basepath);
		swagger20doc.put("host", url.getHost());
		swagger20doc.put("basePath", url.getPath());
		
		JSONArray schemes = new JSONArray();
		schemes.add(url.getProtocol());
		swagger20doc.put("schemes", schemes);		
		
		//securityDefinitions
		if(swagger12doc.containsKey("authorizations")){
			JSONObject securityDefinitions = new JSONObject();
			
			JSONObject authorizations = (JSONObject) swagger12doc.get("authorizations");
			Set authTypes = authorizations.keySet();
			for (Object obj : authTypes) {
				JSONObject authObj = (JSONObject) authorizations.get(obj.toString());
				String type = (String) authObj.get("type");
				//TODO continue from here
			}
		    swagger20doc.put("securityDefinitions", securityDefinitions);		
		}
		
		return swagger20doc;
	}

	/**
	 * generate swagger v2 info object using swagger 1.2 doc.
	 * See <a href="https://github.com/swagger-api/swagger-spec/blob/master/versions/2.0.md#infoObject">Swagger 2 Specification</a>
	 * @param swagger12doc
	 * @return swagger v2 infoObject
	 * @throws ParseException
	 */
	private static JSONObject generateInfoObject(JSONObject swagger12doc) throws ParseException {
		
		JSONObject infoObj = (JSONObject) swagger12doc.get("info");
		JSONParser parser = new JSONParser();
		String swagger2Info = "{\"title\" : \"\", \"version\" : \"\"}";
		
		JSONObject swagger2InfoObj = (JSONObject) parser.parse(swagger2Info);
		
		//set the required parameters first
		String title = (String) infoObj.get("title");
		String version = (String) swagger12doc.get("apiVersion");
		
		swagger2InfoObj.put("title", title);
		swagger2InfoObj.put("version", version);
		
		if(infoObj.containsKey("description")){
			swagger2InfoObj.put("description", (String) infoObj.get("description"));
		}
		if(infoObj.containsKey("termsOfServiceUrl")){
			swagger2InfoObj.put("termsOfService", (String) infoObj.get("termsOfServiceUrl"));
		}
		
		//contact object
		if(infoObj.containsKey("contact")){
			JSONObject contactsObj = new JSONObject();
			String contact = (String) infoObj.get("contact");
			if(contact.contains("http")) {
				contactsObj.put("url", contact);
			} else if (contact.contains("@")) {
				contactsObj.put("email", contact);
			} else {
				contactsObj.put("name", contact);
			}
			
			swagger2InfoObj.put("contact", contactsObj);
		}
		
		//licence object
		JSONObject licenseObj = new JSONObject();
		if(infoObj.containsKey("license")){
			licenseObj.put("name", (String) infoObj.get("license"));
		}
		if(infoObj.containsKey("licenseUrl")){
			licenseObj.put("url", (String) infoObj.get("licenseUrl"));
		}
		if(!licenseObj.isEmpty()){
			swagger2InfoObj.put("license", licenseObj);
		}	
	
		return swagger2InfoObj;
	}
	
	/**
	 * generate swagger v2 paths object using swagger 1.2 doc.
	 * See <a href="https://github.com/swagger-api/swagger-spec/blob/master/versions/2.0.md#pathsObject">Swagger 2 Specification</a>
	 * @param apiDefsPaths map containing swagger v1.2 operations object against its resource path
	 * @return swagger v2 pathsObject
	 * @throws ParseException
	 */
	private static JSONObject generatePathObj(Map<String, JSONArray> apiDefsPaths) throws ParseException {
		JSONObject pathObj = new JSONObject();
		JSONParser parser = new JSONParser();
		
		for (Map.Entry<String, JSONArray> entry : apiDefsPaths.entrySet()) {
		    String key = entry.getKey();
		    JSONArray operations = entry.getValue();
		    JSONObject pathItemObj = new JSONObject();

		    for (int i = 0; i < operations.size(); i++) {
		    	 JSONObject operation = (JSONObject) operations.get(i);
		    	 String method = (String) operation.get("method");
		    	 JSONArray swagger2ParamObj = (JSONArray) operation.get("parameters");
		    	 JSONObject swagger2OperationsObj = new JSONObject();

		    	 JSONArray newParameters = new JSONArray();

		    	 for(int j = 0; j < swagger2ParamObj.size(); j ++){
		    		 JSONObject oldParam = (JSONObject) swagger2ParamObj.get(j);
		    		 JSONObject paramObj = new JSONObject();
		    		 paramObj.put("name",oldParam.get("name"));
		    		 paramObj.put("in",oldParam.get("paramType"));
		    		 paramObj.put("required", oldParam.get("required"));
		    		 if(paramObj.containsKey("description")){
		    			 paramObj.put("description",oldParam.get("description"));
		    		 } else {
		    			 paramObj.put("description","");
		    		 }

		    		 //TODO fill it if necessary, ex type, items etc
		    		 newParameters.add(paramObj);
		    	 }

		    	 //generate the Operation object
		    	 //https://github.com/swagger-api/swagger-spec/blob/master/versions/2.0.md#operationObject
		    	 swagger2OperationsObj.put("operationId", (String) operation.get("nickname"));
		    	 //setting operation level params
		    	 swagger2OperationsObj.put("parameters", newParameters);

		    	 if(operation.containsKey("notes")){
		    		 swagger2OperationsObj.put("description", (String) operation.get("notes"));
		    	 }
		    	 if(operation.containsKey("summary")){
		    		 swagger2OperationsObj.put("summary", (String) operation.get("summary"));
		    	 }


		    	 //set pathItem object for the resource
		    	 //https://github.com/swagger-api/swagger-spec/blob/master/versions/2.0.md#pathItemObject
		    	 pathItemObj.put(method.toLowerCase(), swagger2OperationsObj);
		    	 //TODO Check this param. A list of parameters that are applicable for all the
		    	 //operations described under this path. These parameters can be overridden at the
		    	 //operation level
		    	 pathItemObj.put("parameters", new JSONArray());

		    	 //set the responseMessages. this is a required field. Set a default value if empty
		    	 //https://github.com/swagger-api/swagger-spec/blob/master/versions/2.0.md#responsesObject
		    	 JSONObject responses = null;
		    	 if(operation.containsKey("responseMessages")){
		    		 responses = new JSONObject();
		    		 JSONArray responseMessages = (JSONArray) operation.get("responseMessages");
		    		 for(int x = 0; x < responseMessages.size(); x++){
		    			 JSONObject errorObj = (JSONObject) responseMessages.get(x);
		    			 responses.put(errorObj.get("code"), errorObj.get("message"));
		    		 }
		    	 }
		    	 if(responses == null) {
		    		 //set a default respose message
		    		 //TODO add response codes and update the message
		    		 responses = (JSONObject) parser.parse(Constants.DEFAULT_RESPONSE);
		    	 }
		    	 //pathItemObj.put("responses", responses);
		    	 swagger2OperationsObj.put("responses", responses);

		    	 //TODO --------IMPORTANT --- where to put the throttling_tier, auth_type, scope
		    	 //inside the operation object in 1.2 api def
		    	 String scope = null;
		    	 if(operation.containsKey("scope")){
		    		 scope = (String) operation.get("scope");
		    	 }
		    	 String throttlingTier = (String) operation.get("throttling_tier");
		    	 String authType = (String) operation.get("auth_type");

		    	 //set the security object. These above mention params might be a part of the
		    	 //security objec??
		    	 //https://github.com/swagger-api/swagger-spec/blob/master/versions/2.0.md#securityRequirementObject
		    	 JSONArray swagger2securityObj = new JSONArray();
		    	 //pathItemObj.put("security", new JSONArray());
		    	 //----------------------------------------------------------------
			}
		    pathObj.put(key, pathItemObj);
		}		
		
		return pathObj;
	}


    //https://github.com/swagger-api/swagger-spec/blob/master/versions/2.0.md#paths-object
    private static JSONObject generatePathsObj(Map<String, JSONArray> apiDefinitionPaths) throws ParseException {
        JSONObject pathsObj = new JSONObject();
        JSONParser jsonParser = new JSONParser();

        for (Map.Entry<String, JSONArray> entry : apiDefinitionPaths.entrySet()) {
            String key = entry.getKey();
            JSONArray operations = entry.getValue();
            //https://github.com/swagger-api/swagger-spec/blob/master/versions/2.0.md#path-item-object
            JSONObject pathItemObj = new JSONObject();

            for (Object operation : operations) {
                JSONObject operationObject = (JSONObject) operation;
                String method = (String) operationObject.get("method");
                JSONArray swagger2ParamObjs = (JSONArray) operationObject.get("parameters");
                JSONObject swagger2OperationsObj = new JSONObject();
                JSONArray newParameters = new JSONArray();

                for (Object swagger2ParamObj : swagger2ParamObjs) {
                    JSONObject oldParam = (JSONObject) swagger2ParamObj;
                    JSONObject paramObj = new JSONObject();
                    paramObj.put("name", oldParam.get("name"));
                    paramObj.put("in", oldParam.get("paramType"));
                    paramObj.put("required", oldParam.get("required"));
                    if (paramObj.containsKey("description")) {
                        paramObj.put("description", oldParam.get("description"));
                    } else {
                        paramObj.put("description", "");
                    }

                    //TODO fill it if necessary, ex type, items etc
                    newParameters.add(paramObj);

                }

                //generate the Operation object
                //https://github.com/swagger-api/swagger-spec/blob/master/versions/2.0.md#operationObject
                swagger2OperationsObj.put("operationId", operationObject.get("nickname"));
                //setting operation level params
                swagger2OperationsObj.put("parameters", newParameters);

                if(operationObject.containsKey("notes")){
                    swagger2OperationsObj.put("description", operationObject.get("notes"));
                }
                if(operationObject.containsKey("summary")){
                    swagger2OperationsObj.put("summary", operationObject.get("summary"));
                }

                //set pathItem object for the resource
                //https://github.com/swagger-api/swagger-spec/blob/master/versions/2.0.md#pathItemObject
                pathItemObj.put(method.toLowerCase(), swagger2OperationsObj);
                //TODO Check this param. A list of parameters that are applicable for all the
                //operations described under this path. These parameters can be overridden at the
                //operation level
                pathItemObj.put("parameters", new JSONArray());

                //set the responseMessages. this is a required field. Set a default value if empty
                //https://github.com/swagger-api/swagger-spec/blob/master/versions/2.0.md#responsesObject
                JSONObject responses = null;
                if(operationObject.containsKey("responseMessages")){
                    responses = new JSONObject();
                    JSONArray responseMessages = (JSONArray) operationObject.get("responseMessages");
                    for(int x = 0; x < responseMessages.size(); x++){
                        JSONObject errorObj = (JSONObject) responseMessages.get(x);
                        responses.put(errorObj.get("code"), errorObj.get("message"));
                    }
                }
                if(responses == null) {
                    //set a default response message
                    //TODO add response codes and update the message
                    responses = (JSONObject) jsonParser.parse(Constants.DEFAULT_RESPONSE);
                }
                //pathItemObj.put("responses", responses);
                swagger2OperationsObj.put("responses", responses);

                //TODO --------IMPORTANT --- where to put the throttling_tier, auth_type, scope
                //inside the operation object in 1.2 api def
                String scope = null;
                if(operationObject.containsKey("scope")){
                    scope = (String) operationObject.get("scope");
                }
                String throttlingTier = (String) operationObject.get("throttling_tier");
                String authType = (String) operationObject.get("auth_type");

                //set the security object. These above mention params might be a part of the
                //security objec??
                //https://github.com/swagger-api/swagger-spec/blob/master/versions/2.0.md#securityRequirementObject
                JSONArray swagger2securityObj = new JSONArray();
                //pathItemObj.put("security", new JSONArray());
                //----------------------------------------------------------------
            }
            pathsObj.put(key, pathItemObj);
        }
        return pathsObj;
    }
 
}