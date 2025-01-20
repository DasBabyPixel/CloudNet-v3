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

package eu.cloudnetservice.modules.bridge.impl;

import eu.cloudnetservice.driver.registry.ServiceRegistry;
import eu.cloudnetservice.modules.bridge.BridgeManagement;
import lombok.NonNull;

public interface InternalBridgeManagement extends BridgeManagement {

  /**
   * Registers all services provided by this bridge management to the given service registry.
   *
   * @param registry the registry to register the services in.
   * @throws NullPointerException if the given registry is null.
   */
  void registerServices(@NonNull ServiceRegistry registry);

  /**
   * Execute the post initialization of the bridge management. Populating the caches and applying startup time actions.
   */
  void postInit();

}
