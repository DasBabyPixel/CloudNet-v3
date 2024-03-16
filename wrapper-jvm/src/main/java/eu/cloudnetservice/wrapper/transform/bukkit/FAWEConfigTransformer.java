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

import static org.objectweb.asm.Opcodes.RETURN;

import eu.cloudnetservice.wrapper.transform.Transformer;
import lombok.NonNull;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;

public class FAWEConfigTransformer implements Transformer {

  @Override
  public void transform(@NonNull String classname, @NonNull ClassNode classNode) {
    for (var method : classNode.methods) {
      if (method.name.equals("setAccessible")) {
        method.instructions.insert(new InsnNode(RETURN));
        return;
      }
    }
  }

  @Override
  public byte[] toByteArray(ClassNode classNode) {
    var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
      @Override
      protected String getCommonSuperClass(String type1, String type2) {
        if (type1.equals("com/boydti/fawe/config/Config$BlockName")
          || type2.equals("com/boydti/fawe/config/Config$BlockName")
          || type1.equals("com/boydti/fawe/config/Config")
          || type2.equals("com/boydti/fawe/config/Config")
        ) {
          return Type.getInternalName(Object.class);
        }
        return super.getCommonSuperClass(type1, type2);
      }
    };
    classNode.accept(writer);
    return writer.toByteArray();
  }
}
