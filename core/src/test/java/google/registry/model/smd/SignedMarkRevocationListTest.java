// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.model.smd;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.smd.SignedMarkRevocationList.SHARD_SIZE;
import static google.registry.model.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.JUnitBackports.assertThrows;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.Duration.standardDays;

import com.google.common.collect.ImmutableMap;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SignedMarkRevocationList}. */
@RunWith(JUnit4.class)
public class SignedMarkRevocationListTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();
  private final FakeClock clock = new FakeClock(DateTime.parse("2013-01-01T00:00:00Z"));

  @Test
  public void testUnshardedSaveFails() {
    // Our @Entity's @OnSave method will notice that this shouldn't be saved.
    assertThrows(
        SignedMarkRevocationList.UnshardedSaveException.class,
        () ->
            tm()
                .transact(
                    () -> {
                      SignedMarkRevocationList smdrl =
                          SignedMarkRevocationList.create(
                              tm().getTransactionTime(),
                              ImmutableMap.of("a", tm().getTransactionTime()));
                      smdrl.id = 1; // Without an id this won't save anyways.
                      ofy().saveWithoutBackup().entity(smdrl).now();
                    }));
  }

  @Test
  public void testEmpty() {
    // When Datastore is empty, it should give us an empty thing.
    assertThat(SignedMarkRevocationList.get())
        .isEqualTo(SignedMarkRevocationList.create(START_OF_TIME, ImmutableMap.of()));
  }

  @Test
  public void testSharding2() {
    final int rows = SHARD_SIZE + 1;
    // Create a SignedMarkRevocationList that will need 2 shards to save.
    ImmutableMap.Builder<String, DateTime> revokes = new ImmutableMap.Builder<>();
    for (int i = 0; i < rows; i++) {
      revokes.put(Integer.toString(i), clock.nowUtc());
    }
    // Save it with sharding, and make sure that reloading it works.
    SignedMarkRevocationList unsharded = SignedMarkRevocationList
        .create(clock.nowUtc(), revokes.build())
        .save();
    assertThat(SignedMarkRevocationList.get()).isEqualTo(unsharded);
    assertThat(ofy().load().type(SignedMarkRevocationList.class).count()).isEqualTo(2);
  }

  @Test
  public void testSharding4() {
    final int rows = SHARD_SIZE * 3 + 1;
    // Create a SignedMarkRevocationList that will need 4 shards to save.
    ImmutableMap.Builder<String, DateTime> revokes = new ImmutableMap.Builder<>();
    for (int i = 0; i < rows; i++) {
      revokes.put(Integer.toString(i), clock.nowUtc());
    }
    // Save it with sharding, and make sure that reloading it works.
    SignedMarkRevocationList unsharded = SignedMarkRevocationList
        .create(clock.nowUtc(), revokes.build())
        .save();
    assertThat(SignedMarkRevocationList.get()).isEqualTo(unsharded);
    assertThat(ofy().load().type(SignedMarkRevocationList.class).count()).isEqualTo(4);
  }

  private SignedMarkRevocationList createSaveGetHelper(int rows) {
    ImmutableMap.Builder<String, DateTime> revokes = new ImmutableMap.Builder<>();
    for (int i = 0; i < rows; i++) {
      revokes.put(Integer.toString(i), clock.nowUtc());
    }
    SignedMarkRevocationList.create(clock.nowUtc(), revokes.build()).save();
    SignedMarkRevocationList res = SignedMarkRevocationList.get();
    assertThat(res.size()).isEqualTo(rows);
    return res;
  }

  @Test
  public void test_isSmdRevoked_null() {
    assertThrows(
        NullPointerException.class,
        () ->
            SignedMarkRevocationList.create(START_OF_TIME, ImmutableMap.of())
                .isSmdRevoked(null, clock.nowUtc()));
  }

  @Test
  public void test_isSmdRevoked_garbage() {
    SignedMarkRevocationList smdrl = createSaveGetHelper(SHARD_SIZE + 1);
    assertThat(smdrl.getCreationTime()).isEqualTo(clock.nowUtc());
    assertThat(smdrl.isSmdRevoked("rofl", clock.nowUtc())).isFalse();
    assertThat(smdrl.isSmdRevoked("31337", clock.nowUtc())).isFalse();
  }

  @Test
  public void test_getCreationTime() {
    clock.setTo(DateTime.parse("2000-01-01T00:00:00Z"));
    createSaveGetHelper(5);
    assertThat(SignedMarkRevocationList.get().getCreationTime())
        .isEqualTo(DateTime.parse("2000-01-01T00:00:00Z"));
    clock.advanceBy(standardDays(1));
    assertThat(SignedMarkRevocationList.get().getCreationTime())
        .isEqualTo(DateTime.parse("2000-01-01T00:00:00Z"));
  }

  @Test
  public void test_isSmdRevoked_present() {
    final int rows = SHARD_SIZE + 1;
    SignedMarkRevocationList smdrl = createSaveGetHelper(rows);
    assertThat(smdrl.isSmdRevoked("0", clock.nowUtc())).isTrue();
    assertThat(smdrl.isSmdRevoked(Integer.toString(rows - 1), clock.nowUtc())).isTrue();
    assertThat(smdrl.isSmdRevoked(Integer.toString(rows), clock.nowUtc())).isFalse();
  }

  @Test
  public void test_isSmdRevoked_future() {
    final int rows = SHARD_SIZE;
    SignedMarkRevocationList smdrl = createSaveGetHelper(rows);
    clock.advanceOneMilli();
    assertThat(smdrl.isSmdRevoked("0", clock.nowUtc())).isTrue();
    assertThat(smdrl.isSmdRevoked(Integer.toString(rows - 1), clock.nowUtc())).isTrue();
    assertThat(smdrl.isSmdRevoked(Integer.toString(rows), clock.nowUtc())).isFalse();
  }

  @Test
  public void test_isSmdRevoked_past() {
    final int rows = SHARD_SIZE;
    SignedMarkRevocationList smdrl = createSaveGetHelper(rows);
    clock.setTo(clock.nowUtc().minusMillis(1));
    assertThat(smdrl.isSmdRevoked("0", clock.nowUtc())).isFalse();
    assertThat(smdrl.isSmdRevoked(Integer.toString(rows - 1), clock.nowUtc())).isFalse();
    assertThat(smdrl.isSmdRevoked(Integer.toString(rows), clock.nowUtc())).isFalse();
  }
}
