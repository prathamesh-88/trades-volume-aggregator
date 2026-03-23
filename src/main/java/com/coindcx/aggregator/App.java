package com.coindcx.aggregator;

import com.coindcx.aggregator.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class App {

    private static final Logger LOG = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        AppConfig config = new AppConfig();
        AggregatedServices services = AggregatedServices.start(config);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutdown hook triggered");
            services.close();
        }, "shutdown-hook"));

        services.startBackground();
        services.await();
    }
}
