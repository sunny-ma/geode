/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.distributed.internal.membership.gms.locator;

import static org.apache.geode.distributed.ConfigurationProperties.BIND_ADDRESS;
import static org.apache.geode.distributed.ConfigurationProperties.DISABLE_TCP;
import static org.apache.geode.distributed.ConfigurationProperties.LOCATORS;
import static org.apache.geode.distributed.internal.membership.adapter.TcpSocketCreatorAdapter.asTcpSocketCreator;
import static org.apache.geode.distributed.internal.membership.gms.locator.GMSLocator.LOCATOR_FILE_STAMP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.util.Properties;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;

import org.apache.geode.DataSerializer;
import org.apache.geode.distributed.Locator;
import org.apache.geode.distributed.internal.ClusterDistributionManager;
import org.apache.geode.distributed.internal.DMStats;
import org.apache.geode.distributed.internal.Distribution;
import org.apache.geode.distributed.internal.DistributionConfigImpl;
import org.apache.geode.distributed.internal.DistributionImpl;
import org.apache.geode.distributed.internal.InternalDistributedSystem;
import org.apache.geode.distributed.internal.InternalLocator;
import org.apache.geode.distributed.internal.LocatorStats;
import org.apache.geode.distributed.internal.membership.api.MemberIdentifier;
import org.apache.geode.distributed.internal.membership.api.MembershipListener;
import org.apache.geode.distributed.internal.membership.api.MessageListener;
import org.apache.geode.distributed.internal.membership.gms.GMSMembershipView;
import org.apache.geode.distributed.internal.membership.gms.Services;
import org.apache.geode.distributed.internal.tcpserver.TcpClient;
import org.apache.geode.internal.AvailablePortHelper;
import org.apache.geode.internal.InternalDataSerializer;
import org.apache.geode.internal.admin.remote.RemoteTransportConfig;
import org.apache.geode.internal.inet.LocalHostUtil;
import org.apache.geode.internal.net.SocketCreatorFactory;
import org.apache.geode.internal.security.SecurableCommunicationChannel;
import org.apache.geode.internal.serialization.DSFIDSerializer;
import org.apache.geode.internal.serialization.Version;
import org.apache.geode.test.junit.categories.MembershipTest;

@Category(MembershipTest.class)
public class GMSLocatorRecoveryIntegrationTest {

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Rule
  public TestName testName = new TestName();

  private File stateFile;
  private GMSLocator gmsLocator;
  private Locator locator;
  private DSFIDSerializer serializer;
  private Distribution distribution;

  @Before
  public void setUp() throws Exception {

    SocketCreatorFactory.setDistributionConfig(new DistributionConfigImpl(new Properties()));

    serializer = InternalDataSerializer.getDSFIDSerializer();
    Services.registerSerializables(serializer);
    Version current = Version.CURRENT; // force version initialization

    stateFile = new File(temporaryFolder.getRoot(), getClass().getSimpleName() + "_locator.dat");

    gmsLocator = new GMSLocator(null, null, false, false, new LocatorStats(), "",
        temporaryFolder.getRoot().toPath(), new TcpClient(
            asTcpSocketCreator(
                SocketCreatorFactory
                    .getSocketCreatorForComponent(SecurableCommunicationChannel.LOCATOR)),
            InternalDataSerializer.getDSFIDSerializer().getObjectSerializer(),
            InternalDataSerializer.getDSFIDSerializer().getObjectDeserializer()),
        InternalDataSerializer.getDSFIDSerializer().getObjectSerializer(),
        InternalDataSerializer.getDSFIDSerializer().getObjectDeserializer());
    gmsLocator.setViewFile(stateFile);
  }

  @After
  public void tearDown() throws Exception {
    if (distribution != null) {
      distribution.disconnect(false);
    }
    if (locator != null) {
      locator.stop();
    }
    SocketCreatorFactory.close();
  }

  @Test
  public void testRecoverFromFileWithNonExistFile() {
    assertThat(stateFile).doesNotExist();

    assertThat(gmsLocator.recoverFromFile(stateFile)).isFalse();
  }

  @Test
  public void testRecoverFromFileWithNormalFile() throws Exception {
    GMSMembershipView view = new GMSMembershipView();
    populateStateFile(stateFile, LOCATOR_FILE_STAMP, Version.CURRENT_ORDINAL, view);

    assertThat(gmsLocator.recoverFromFile(stateFile)).isTrue();
  }

  @Test
  public void testRecoverFromFileWithWrongFileStamp() throws Exception {
    // add 1 to file stamp to make it invalid
    populateStateFile(stateFile, LOCATOR_FILE_STAMP + 1, Version.CURRENT_ORDINAL, 1);

    assertThat(gmsLocator.recoverFromFile(stateFile)).isFalse();
  }

  @Test
  public void testRecoverFromFileWithWrongOrdinal() throws Exception {
    // add 1 to ordinal to make it wrong
    populateStateFile(stateFile, LOCATOR_FILE_STAMP, Version.CURRENT_ORDINAL + 1, 1);

    boolean recovered = gmsLocator.recoverFromFile(stateFile);
    assertThat(recovered).isFalse();
  }

  @Test
  public void testRecoverFromFileWithInvalidViewObject() throws Exception {
    populateStateFile(stateFile, LOCATOR_FILE_STAMP, Version.CURRENT_ORDINAL, 1);

    Throwable thrown = catchThrowable(() -> gmsLocator.recoverFromFile(stateFile));

    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("Unable to recover previous membership view from");
  }

  @Test
  public void testRecoverFromOther() throws Exception {
    int port = AvailablePortHelper.getRandomAvailableTCPPort();
    InetAddress localHost = LocalHostUtil.getLocalHost();

    // this locator will hook itself up with the first Membership to be created
    locator = InternalLocator.startLocator(port, null, null, null, localHost, false,
        new Properties(), null, temporaryFolder.getRoot().toPath());

    // create configuration objects
    Properties nonDefault = new Properties();
    nonDefault.setProperty(BIND_ADDRESS, localHost.getHostAddress());
    nonDefault.setProperty(DISABLE_TCP, "true");
    nonDefault.setProperty(LOCATORS, localHost.getHostAddress() + '[' + port + ']');

    DistributionConfigImpl config = new DistributionConfigImpl(nonDefault);
    RemoteTransportConfig transport =
        new RemoteTransportConfig(config, MemberIdentifier.NORMAL_DM_TYPE);

    MembershipListener mockListener = mock(MembershipListener.class);
    MessageListener mockMessageListener = mock(MessageListener.class);
    InternalDistributedSystem mockSystem = mock(InternalDistributedSystem.class);
    ClusterDistributionManager mockClusterDistributionManager =
        mock(ClusterDistributionManager.class);
    DMStats mockDmStats = mock(DMStats.class);

    when(mockSystem.getConfig()).thenReturn(config);

    final TcpClient locatorClient = new TcpClient(
        asTcpSocketCreator(
            SocketCreatorFactory
                .getSocketCreatorForComponent(SecurableCommunicationChannel.LOCATOR)),
        InternalDataSerializer.getDSFIDSerializer().getObjectSerializer(),
        InternalDataSerializer.getDSFIDSerializer().getObjectDeserializer());

    distribution =
        new DistributionImpl(mockClusterDistributionManager, transport, mockSystem, mockListener,
            mockMessageListener);
    distribution.start();

    GMSLocator gmsLocator = new GMSLocator(localHost,
        distribution.getLocalMember().getHost() + "[" + port + "]", true, true,
        new LocatorStats(), "", temporaryFolder.getRoot().toPath(), locatorClient,
        InternalDataSerializer.getDSFIDSerializer().getObjectSerializer(),
        InternalDataSerializer.getDSFIDSerializer().getObjectDeserializer());
    gmsLocator.setViewFile(new File(temporaryFolder.getRoot(), "locator2.dat"));
    gmsLocator.init(null);

    assertThat(gmsLocator.getMembers())
        .contains(distribution.getLocalMember());
  }

  @Test
  public void testViewFileNotFound() throws Exception {
    populateStateFile(stateFile, LOCATOR_FILE_STAMP, Version.CURRENT_ORDINAL,
        new GMSMembershipView());
    assertThat(stateFile).exists();

    File dir = temporaryFolder.newFolder(testName.getMethodName());
    File viewFileInNewDirectory = new File(dir, stateFile.getName());
    assertThat(viewFileInNewDirectory).doesNotExist();

    File locatorViewFile = gmsLocator.setViewFile(viewFileInNewDirectory);
    assertThat(gmsLocator.recoverFromFile(locatorViewFile)).isFalse();
  }

  private void populateStateFile(File file, int fileStamp, int ordinal, Object object)
      throws IOException {
    try (FileOutputStream fileStream = new FileOutputStream(file);
        ObjectOutputStream oos = new ObjectOutputStream(fileStream)) {
      oos.writeInt(fileStamp);
      oos.writeInt(ordinal);
      oos.flush();
      DataOutput dataOutput = new DataOutputStream(fileStream);
      DataSerializer.writeObject(object, dataOutput);
      fileStream.flush();
    }
  }
}
