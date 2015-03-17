package org.wso2.carbon.apimgt.migration.internal;

import org.wso2.carbon.registry.core.service.RegistryService;
import org.wso2.carbon.registry.core.service.TenantRegistryLoader;
import org.wso2.carbon.user.core.service.RealmService;

public class ServiceHolder {
    //Registry Service which is used to get registry data.
    private static RegistryService registryService;

    //Realm Service which is used to get tenant data.
    private static RealmService realmService;

    private static TenantRegistryLoader tenantRegLoader;

    /**
     * Method to get RegistryService.
     *
     * @return registryService.
     */
    public static RegistryService getRegistryService() {
        return registryService;
    }

    /**
     * Method to set registry RegistryService.
     *
     * @param service registryService.
     */
    public static void setRegistryService(RegistryService service) {
        registryService = service;
    }

    /**
     * This method used to get RealmService.
     *
     * @return RealmService.
     */
    public static RealmService getRealmService() {
        return realmService;
    }

    /**
     * Method to set registry RealmService.
     *
     * @param service RealmService.
     */
    public static void setRealmService(RealmService service) {
        realmService = service;
    }

    /**
     * This method used to get TenantRegistryLoader
     *
     * @return tenantRegLoader  Tenant registry loader for load tenant registry
     */
    public static TenantRegistryLoader getTenantRegLoader() {
        return tenantRegLoader;
    }

    /**
     * This method used to set TenantRegistryLoader
     *
     * @param service Tenant registry loader for load tenant registry
     */
    public static void setTenantRegLoader(TenantRegistryLoader service) {
        tenantRegLoader = service;
    }
}