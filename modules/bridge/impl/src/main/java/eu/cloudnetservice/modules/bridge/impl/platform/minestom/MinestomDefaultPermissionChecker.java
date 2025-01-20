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

package eu.cloudnetservice.modules.bridge.impl.platform.minestom;

import eu.cloudnetservice.driver.registry.AutoService;
import lombok.NonNull;
import net.minestom.server.entity.Player;

@AutoService(services = MinestomPermissionChecker.class, name = "default", markAsDefault = true)
public class MinestomDefaultPermissionChecker implements MinestomPermissionChecker {

  @Override
  public boolean hasPermission(@NonNull Player player, @NonNull String permission) {
    return player.getPermissionLevel() > 0;
  }
}
