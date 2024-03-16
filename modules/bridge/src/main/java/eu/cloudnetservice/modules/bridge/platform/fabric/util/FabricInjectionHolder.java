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

package eu.cloudnetservice.modules.bridge.platform.fabric.util;

import eu.cloudnetservice.driver.event.EventManager;
import eu.cloudnetservice.driver.network.NetworkClient;
import eu.cloudnetservice.driver.network.rpc.RPCFactory;
import eu.cloudnetservice.driver.provider.CloudServiceProvider;
import eu.cloudnetservice.driver.provider.ServiceTaskProvider;
import eu.cloudnetservice.driver.registry.ServiceRegistry;
import eu.cloudnetservice.modules.bridge.BridgeServiceHelper;
import eu.cloudnetservice.modules.bridge.platform.helper.ServerPlatformHelper;
import eu.cloudnetservice.wrapper.configuration.WrapperConfiguration;
import eu.cloudnetservice.wrapper.holder.ServiceInfoHolder;
import jakarta.inject.Singleton;
import lombok.NonNull;

@Singleton
public record FabricInjectionHolder(
  @NonNull RPCFactory rpcFactory,
  @NonNull EventManager eventManager,
  @NonNull NetworkClient networkClient,
  @NonNull ServiceRegistry serviceRegistry,
  @NonNull ServiceTaskProvider taskProvider,
  @NonNull BridgeServiceHelper serviceHelper,
  @NonNull ServiceInfoHolder serviceInfoHolder,
  @NonNull CloudServiceProvider serviceProvider,
  @NonNull ServerPlatformHelper serverPlatformHelper,
  @NonNull WrapperConfiguration wrapperConfiguration
) {

}
