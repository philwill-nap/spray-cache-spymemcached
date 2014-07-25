package org.greencheek.elasticacheconfig.client;

/**
 * Created by dominictootell on 25/07/2014.
 */
public class SingleElastiCacheConfigServerChooser implements ElastiCacheConfigServerChooser{

    private final ElastiCacheServerConnectionDetails server;

    public SingleElastiCacheConfigServerChooser(ElastiCacheServerConnectionDetails server) {
        this.server = server;
    }

    @Override
    public ElastiCacheServerConnectionDetails getServer() {
        return server;
    }
}
