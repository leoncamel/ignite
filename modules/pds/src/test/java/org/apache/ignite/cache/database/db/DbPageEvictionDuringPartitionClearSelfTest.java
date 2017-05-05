package org.apache.ignite.cache.database.db;

import java.util.concurrent.Callable;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteDataStreamer;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheRebalanceMode;
import org.apache.ignite.cache.CacheWriteSynchronizationMode;
import org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.MemoryConfiguration;
import org.apache.ignite.configuration.MemoryPolicyConfiguration;
import org.apache.ignite.configuration.PersistenceConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.processors.cache.database.GridCacheDatabaseSharedManager;
import org.apache.ignite.internal.processors.cache.database.wal.FileWriteAheadLogManager;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;

/**
 *
 */
public class DbPageEvictionDuringPartitionClearSelfTest extends GridCommonAbstractTest {
    /** */
    public static final String CACHE_NAME = "cache";

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        CacheConfiguration ccfg = new CacheConfiguration("cache")
            .setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL)
            .setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC)
            .setAffinity(new RendezvousAffinityFunction(false, 128))
            .setRebalanceMode(CacheRebalanceMode.SYNC)
            .setBackups(1);

        cfg.setCacheConfiguration(ccfg);

        MemoryConfiguration memCfg = new MemoryConfiguration();

        // Intentionally set small page cache size.

        MemoryPolicyConfiguration memPlcCfg = new MemoryPolicyConfiguration();

        memPlcCfg.setSize(70 * 1024 * 1024);

        memPlcCfg.setName("dfltMemPlc");

        memCfg.setMemoryPolicies(memPlcCfg);

        memCfg.setDefaultMemoryPolicyName(memPlcCfg.getName());

        cfg.setMemoryConfiguration(memCfg);

        cfg.setPersistenceConfiguration(new PersistenceConfiguration());

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected long getTestTimeout() {
        return 20 * 60 * 1000;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        System.setProperty(FileWriteAheadLogManager.IGNITE_PDS_WAL_MODE, "LOG_ONLY");
        System.setProperty(GridCacheDatabaseSharedManager.IGNITE_PDS_CHECKPOINT_TEST_SKIP_SYNC, "true");
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        System.clearProperty(FileWriteAheadLogManager.IGNITE_PDS_WAL_MODE);
        System.clearProperty(GridCacheDatabaseSharedManager.IGNITE_PDS_CHECKPOINT_TEST_SKIP_SYNC);
    }

    /**
     * @throws Exception if failed.
     */
    public void testPageEvictionOnNodeStart() throws Exception {
        for (int r = 0; r < 3; r++) {
            deleteRecursively(U.resolveWorkDirectory(U.defaultWorkDirectory(), "db", false));

            startGrids(2);

            try {
                Ignite ig = ignite(0);

                IgniteDataStreamer<Object, Object> streamer = ig.dataStreamer(CACHE_NAME);

                for (int i = 0; i < 300_000; i++) {
                    streamer.addData(i, new TestValue(i));

                    if (i > 0 && i % 10_000 == 0)
                        info("Done: " + i);
                }

                streamer.flush();

                IgniteInternalFuture<Object> fut = GridTestUtils.runAsync(new Callable<Object>() {
                    @Override public Object call() throws Exception {
                        IgniteEx ig = startGrid(2);

                        info(">>>>>>>>>>>");
                        info(">>>>>>>>>>>");
                        info(">>>>>>>>>>>");

                        return ig;
                    }
                });

                for (int i = 500_000; i < 1_000_000; i++) {
                    streamer.addData(i, new TestValue(i));

                    if (i > 0 && i % 10_000 == 0) {
                        info("Done: " + i);

                        U.sleep(1000);
                    }
                }

                streamer.close();

                fut.get();
            }
            finally {
                stopAllGrids();

                deleteRecursively(U.resolveWorkDirectory(U.defaultWorkDirectory(), "db", false));
            }
        }
    }

    /**
     *
     */
    private static class TestValue {
        /** */
        private int id;

        /** */
        private byte[] payload = new byte[512];

        /**
         * @param id ID.
         */
        private TestValue(int id) {
            this.id = id;
        }
    }
}