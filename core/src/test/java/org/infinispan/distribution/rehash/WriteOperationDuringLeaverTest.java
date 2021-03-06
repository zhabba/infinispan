package org.infinispan.distribution.rehash;

import static org.infinispan.util.BlockingLocalTopologyManager.replaceTopologyManagerDefaultCache;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.distribution.DistributionInfo;
import org.infinispan.distribution.DistributionManager;
import org.infinispan.distribution.LocalizedCacheTopology;
import org.infinispan.distribution.MagicKey;
import org.infinispan.globalstate.NoOpGlobalConfigurationManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.test.MultipleCacheManagersTest;
import org.infinispan.test.TestingUtil;
import org.infinispan.test.fwk.CleanupAfterMethod;
import org.infinispan.topology.CacheTopology;
import org.infinispan.util.BlockingLocalTopologyManager;
import org.testng.annotations.Test;

/**
 * Test case for ISPN-6599
 * <p>
 * During a rehash, if the backup owner leaves, the new backup isn't in the read consistent hash. However, the
 * EntryWrappingInterceptor checks the ownership in the read consistent hash. The backup update will fail in this node
 * making the data inconsistent.
 *
 * @author Pedro Ruivo
 * @since 9.0
 */
@Test(groups = "functional", testName = "distribution.rehash.WriteOperationDuringLeaverTest")
@CleanupAfterMethod
public class WriteOperationDuringLeaverTest extends MultipleCacheManagersTest {

   private static final int NUMBER_NODES = 3;

   public void testSingleKeyCommandWithExistingKey() throws Exception {
      doTest(Operation.SINGLE_KEY, true);
   }

   public void testMultipleKeyCommandWithExistingKey() throws Exception {
      doTest(Operation.MULTIPLE_KEYS, true);
   }

   public void testSingleKeyCommandWithNewgKey() throws Exception {
      doTest(Operation.SINGLE_KEY, false);
   }

   public void testMultipleKeyCommandWithNewKey() throws Exception {
      doTest(Operation.MULTIPLE_KEYS, false);
   }

   @Override
   protected void createCacheManagers() throws Throwable {
      ConfigurationBuilder builder = getDefaultClusteredCacheConfig(CacheMode.DIST_SYNC, false);
      builder.clustering().hash().numOwners(2);
      createClusteredCaches(NUMBER_NODES, builder);
   }

   @Override
   protected void amendCacheManagerBeforeStart(EmbeddedCacheManager cm) {
      NoOpGlobalConfigurationManager.amendCacheManager(cm);
   }

   private void doTest(Operation operation, boolean init) throws Exception {
      final MagicKey key = new MagicKey(cache(1), cache(2));
      if (init) {
         cache(0).put(key, "v1");
         assertInAllCache(key, "v1");
      }

      BlockingLocalTopologyManager bltm0 = replaceTopologyManagerDefaultCache(manager(0));
      BlockingLocalTopologyManager bltm1 = replaceTopologyManagerDefaultCache(manager(1));

      killMember(2, null, false);

      //CH_UPDATE + REBALANCE_START + CH_UPDATE(blocked)
      bltm0.expectRebalanceStartAfterLeave().unblock();
      bltm1.expectRebalanceStartAfterLeave().unblock();
      bltm0.expectPhaseConfirmation().unblock();
      bltm1.expectPhaseConfirmation().unblock();

      //check if we are in the correct state
      LocalizedCacheTopology cacheTopology = TestingUtil.extractComponent(cache(0), DistributionManager.class).getCacheTopology();
      DistributionInfo distributionInfo = cacheTopology.getDistribution(key);
      assertFalse(distributionInfo.isReadOwner());
      assertTrue(distributionInfo.isWriteOwner());
      assertEquals(address(1), distributionInfo.primary());

      operation.put(key, "v2", cache(1));

      BlockingLocalTopologyManager.finishRebalance(CacheTopology.Phase.READ_ALL_WRITE_ALL, bltm0, bltm1);
      waitForClusterToForm(); //let the cluster finish the state transfer
      assertInAllCache(key, "v2");
   }

   private <K, V> void assertInAllCache(K key, V value) {
      for (Cache<K, V> cache : this.<K, V>caches()) {
         assertEquals("Wrong value in cache " + address(cache), value, cache.get(key));
      }
   }

   //all the single key are handled in the same way. No need to test remove/replace.
   private enum Operation {
      SINGLE_KEY {
         @Override
         <K, V> void put(K key, V value, Cache<K, V> cache) {
            cache.put(key, value);
         }
      },
      MULTIPLE_KEYS {
         @Override
         <K, V> void put(K key, V value, Cache<K, V> cache) {
            Map<K, V> map = new HashMap<>();
            map.put(key, value);
            cache.putAll(map);
         }
      };

      abstract <K, V> void put(K key, V value, Cache<K, V> cache);
   }

   private static class CacheTopologyMatcher extends BaseMatcher<Object> {
      private final int topologyId;

      CacheTopologyMatcher(int topologyId) {
         this.topologyId = topologyId;
      }

      @Override
      public boolean matches(Object item) {
         return (item instanceof CacheTopology) && ((CacheTopology) item).getTopologyId() == topologyId;
      }

      @Override
      public void describeTo(Description description) {
         description.appendText("CacheTopology(" + topologyId + ")");
      }
   }
}
