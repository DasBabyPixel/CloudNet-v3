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

package eu.cloudnetservice.wrapper.transform.bukkit;

import eu.cloudnetservice.wrapper.transform.Transformer;
import lombok.NonNull;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;

public class FAWEJarsTransformer implements Transformer {

  @Override
  public void transform(@NonNull String classname, @NonNull ClassNode classNode) {
    for (var method : classNode.methods) {
      if (method.name.equals("<clinit>")) {
        for (var instruction : method.instructions) {
          if (instruction instanceof LdcInsnNode ldc) {
            if ("https://addons.cursecdn.com/files/2431/372/worldedit-bukkit-6.1.7.2.jar".equals(ldc.cst)) {
              ldc.cst = "https://mediafilez.forgecdn.net/files/2431/372/worldedit-bukkit-6.1.7.2.jar";
            }
          }
        }
      }
    }
  }
}
