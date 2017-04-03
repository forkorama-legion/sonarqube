/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.ce.cluster;

import com.hazelcast.core.HazelcastInstance;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.ExpectedException;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.sonar.api.config.MapSettings;
import org.sonar.api.config.PropertyDefinitions;
import org.sonar.api.config.Settings;
import org.sonar.process.NetworkUtils;
import org.sonar.process.ProcessProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.MapEntry.entry;

public class HazelcastClientWrapperImplTest {
  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Rule
  public TestRule safeGuard = new DisableOnDebug(Timeout.seconds(10));

  private static HazelcastInstance hzCluster;
  private static HazelcastClientWrapperImpl hzClient;

  @BeforeClass
  public static void setupHazelcastClusterAndHazelcastClient() {
    int port = NetworkUtils.freePort();
    hzCluster = HazelcastTestHelper.createHazelcastCluster("cluster_with_client", port);

    Settings settings = createClusterSettings("cluster_with_client", "localhost:" + port);
    hzClient = new HazelcastClientWrapperImpl(settings);
  }

  @AfterClass
  public static void stopHazelcastClusterAndHazelcastClient() {
    try {
      hzClient.stop();
    } catch (Exception e) {
      // Ignore it
    }
    try {
      hzCluster.shutdown();
    } catch (Exception e) {
      // Ignore it
    }
  }

  @Test
  public void constructor_throws_ISE_if_missing_CLUSTER_ENABLED_throw_ISE() {
    Settings settings = createClusterSettings("sonarqube", "localhost:9003");
    settings.removeProperty(ProcessProperties.CLUSTER_ENABLED);

    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("Cluster is not enabled");

    new HazelcastClientWrapperImpl(settings);
  }

  @Test
  public void constructor_throws_ISE_if_missing_CLUSTER_NAME_throw_ISE() {
    Settings settings = createClusterSettings("sonarqube", "localhost:9003");
    settings.removeProperty(ProcessProperties.CLUSTER_NAME);

    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("sonar.cluster.name is missing");

    new HazelcastClientWrapperImpl(settings);
  }

  @Test
  public void constructor_throws_ISE_if_missing_CLUSTER_LOCALENDPOINT_throw_ISE() {
    Settings settings = createClusterSettings("sonarqube", "localhost:9003");
    settings.removeProperty(ProcessProperties.CLUSTER_LOCALENDPOINT);

    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("LocalEndPoint have not been set");

    new HazelcastClientWrapperImpl(settings);
  }

  @Test
  public void client_must_connect_to_hazelcast() {
    int port = NetworkUtils.freePort();
    // Launch a fake Hazelcast instance
    HazelcastInstance hzInstance = HazelcastTestHelper.createHazelcastCluster("client_must_connect_to_hazelcast", port);
    Settings settings = createClusterSettings("client_must_connect_to_hazelcast", "localhost:" + port);

    HazelcastClientWrapperImpl hazelcastClientWrapperImpl = new HazelcastClientWrapperImpl(settings);
    try {
      hazelcastClientWrapperImpl.start();
      assertThat(hazelcastClientWrapperImpl.getConnectedClients()).hasSize(1);
      assertThat(hazelcastClientWrapperImpl.getClientUUID()).isNotEmpty();
    } finally {
      hazelcastClientWrapperImpl.stop();
    }
  }

  @Test
  public void client_must_be_able_to_retrieve_Set_objects() {
    try {
      hzClient.start();

      // Set
      Set<String> setTest = new HashSet<>();
      setTest.addAll(Arrays.asList("8", "9"));
      hzCluster.getSet("TEST1").addAll(setTest);
      assertThat(hzClient.getSet("TEST1")).containsAll(setTest);
    } finally {
      hzClient.stop();
    }
  }

  @Test
  public void client_must_be_able_to_retrieve_List_objects() {
    try {
      hzClient.start();

      // List
      List<String> listTest = Arrays.asList("1", "2");
      hzCluster.getList("TEST2").addAll(listTest);
      assertThat(hzClient.getList("TEST2")).containsAll(listTest);
    } finally {
      hzClient.stop();
    }
  }

  @Test
  public void client_must_be_able_to_retrieve_Map_objects() {
    try {
      hzClient.start();

      Map mapTest = new HashMap<>();
      mapTest.put("a", Arrays.asList("123", "456"));
      hzCluster.getMap("TEST3").putAll(mapTest);
      assertThat(hzClient.getMap("TEST3")).containsExactly(
        entry("a", Arrays.asList("123", "456"))
      );
    } finally {
      hzClient.stop();
    }
  }

  private static Settings createClusterSettings(String name, String localEndPoint) {
    Properties properties = new Properties();
    properties.setProperty(ProcessProperties.CLUSTER_NAME, name);
    properties.setProperty(ProcessProperties.CLUSTER_LOCALENDPOINT, localEndPoint);
    properties.setProperty(ProcessProperties.CLUSTER_ENABLED, "true");
    return new MapSettings(new PropertyDefinitions()).addProperties(properties);
  }
}
