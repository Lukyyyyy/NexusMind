package com.luky.nexusmind.service;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeGraphStoreAvailabilityTest {

    @Test
    void reportsUnavailableWhenDriverCannotConnect() {
        KnowledgeGraphStoreService service = serviceWithDriver(driverThatFailsConnectivity());

        assertFalse(service.isEnabled());
    }

    @Test
    void reportsAvailableOnlyAfterConnectivityCheckSucceeds() {
        KnowledgeGraphStoreService service = serviceWithDriver(driverWithConnectivity());

        assertTrue(service.isEnabled());
    }

    private KnowledgeGraphStoreService serviceWithDriver(Driver driver) {
        ObjectProvider<Driver> provider = new ObjectProvider<>() {
            @Override
            public Driver getIfAvailable() {
                return driver;
            }
        };
        return new KnowledgeGraphStoreService(provider);
    }

    private Driver driverWithConnectivity() {
        return driver((proxy, method, args) -> null);
    }

    private Driver driverThatFailsConnectivity() {
        return driver((proxy, method, args) -> {
            if (method.getName().equals("verifyConnectivity")) {
                throw new IllegalStateException("connection refused");
            }
            return null;
        });
    }

    private Driver driver(java.lang.reflect.InvocationHandler handler) {
        return (Driver) Proxy.newProxyInstance(
                Driver.class.getClassLoader(),
                new Class<?>[]{Driver.class},
                handler);
    }
}
