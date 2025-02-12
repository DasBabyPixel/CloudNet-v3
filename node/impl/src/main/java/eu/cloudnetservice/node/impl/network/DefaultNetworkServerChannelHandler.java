/*
 * Copyright 2019-2024 CloudNetService team & contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.cloudnetservice.node.impl.network;

import eu.cloudnetservice.driver.event.EventManager;
import eu.cloudnetservice.driver.event.events.network.ChannelType;
import eu.cloudnetservice.driver.event.events.network.NetworkChannelCloseEvent;
import eu.cloudnetservice.driver.impl.network.NetworkConstants;
import eu.cloudnetservice.driver.language.I18n;
import eu.cloudnetservice.driver.network.NetworkChannel;
import eu.cloudnetservice.driver.network.NetworkChannelHandler;
import eu.cloudnetservice.driver.network.protocol.Packet;
import eu.cloudnetservice.driver.registry.Service;
import eu.cloudnetservice.node.cluster.NodeServerProvider;
import eu.cloudnetservice.node.cluster.NodeServerState;
import eu.cloudnetservice.node.config.Configuration;
import eu.cloudnetservice.node.impl.network.listener.AuthorizationPacketListener;
import eu.cloudnetservice.node.impl.service.InternalCloudService;
import eu.cloudnetservice.node.impl.util.NetworkUtil;
import eu.cloudnetservice.node.service.CloudServiceManager;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public final class DefaultNetworkServerChannelHandler implements NetworkChannelHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultNetworkServerChannelHandler.class);

  private final I18n i18n;
  private final EventManager eventManager;
  private final NodeNetworkUtil networkUtil;
  private final Configuration configuration;
  private final NodeServerProvider nodeServerProvider;
  private final CloudServiceManager cloudServiceManager;

  @Inject
  public DefaultNetworkServerChannelHandler(
    @NonNull @Service I18n i18n,
    @NonNull EventManager eventManager,
    @NonNull NodeNetworkUtil networkUtil,
    @NonNull Configuration configuration,
    @NonNull NodeServerProvider nodeServerProvider,
    @NonNull CloudServiceManager cloudServiceManager
  ) {
    this.i18n = i18n;
    this.eventManager = eventManager;
    this.networkUtil = networkUtil;
    this.configuration = configuration;
    this.nodeServerProvider = nodeServerProvider;
    this.cloudServiceManager = cloudServiceManager;
  }

  @Override
  public void handleChannelInitialize(@NonNull NetworkChannel channel) {
    // check if the ip of the connecting client is allowed
    if (this.shouldDenyConnection(channel)) {
      channel.close();
      return;
    }

    if (this.networkUtil.shouldInitializeChannel(channel, ChannelType.SERVER_CHANNEL)) {
      // add the auth listener
      channel.packetRegistry().addListener(
        NetworkConstants.INTERNAL_AUTHORIZATION_CHANNEL,
        AuthorizationPacketListener.class);

      LOGGER.debug(this.i18n.translate("server-network-channel-init",
        channel.serverAddress(),
        channel.clientAddress()));
    } else {
      channel.close();
    }
  }

  @Override
  public boolean handlePacketReceive(@NonNull NetworkChannel channel, @NonNull Packet packet) {
    return true;
  }

  @Override
  public void handleChannelClose(@NonNull NetworkChannel channel) {
    this.eventManager.callEvent(new NetworkChannelCloseEvent(channel, ChannelType.SERVER_CHANNEL));

    LOGGER.debug(this.i18n.translate("server-network-channel-close",
      channel.serverAddress(),
      channel.clientAddress()));

    var cloudService = this.cloudServiceManager.localCloudServices()
      .stream()
      .filter(service -> {
        var serviceNetworkChannel = service.networkChannel();
        return serviceNetworkChannel != null && serviceNetworkChannel.equals(channel);
      })
      .findFirst()
      .orElse(null);
    if (cloudService instanceof InternalCloudService internalCloudService) {
      this.closeAsCloudService(internalCloudService, channel);
      return;
    }

    var nodeServer = this.nodeServerProvider.node(channel);
    if (nodeServer != null && nodeServer.state() != NodeServerState.DISCONNECTED) {
      nodeServer.close();
    }
  }

  private void closeAsCloudService(@NonNull InternalCloudService cloudService, @NonNull NetworkChannel channel) {
    // reset the service channel and connection time
    cloudService.networkChannel(null);

    LOGGER.info(this.i18n.translate("cloudnet-service-networking-disconnected",
      cloudService.serviceId().uniqueId(),
      cloudService.serviceId().taskName(),
      cloudService.serviceId().name(),
      channel.serverAddress().host() + ":" + channel.serverAddress().port(),
      channel.clientAddress().host() + ":" + channel.clientAddress().port()));
  }

  private boolean shouldDenyConnection(@NonNull NetworkChannel channel) {
    var ipWhitelist = this.configuration.ipWhitelist();
    var sourceClientAddress = NetworkUtil.removeAddressScope(channel.clientAddress().host());

    // check if any address added to the ip whitelist matches the source client address
    for (var allowedIpAddress : ipWhitelist) {
      var allowedAddressWithoutScope = NetworkUtil.removeAddressScope(allowedIpAddress);
      if (allowedAddressWithoutScope.equals(sourceClientAddress)) {
        return false;
      }
    }

    // no allowed ip found that matches the given client address
    return true;
  }
}
