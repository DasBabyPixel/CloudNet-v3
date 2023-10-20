/*
 * Copyright 2019-2023 CloudNetService team & contributors
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

import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Type.BOOLEAN_TYPE;
import static org.objectweb.asm.Type.VOID_TYPE;
import static org.objectweb.asm.Type.getInternalName;
import static org.objectweb.asm.Type.getMethodDescriptor;
import static org.objectweb.asm.Type.getType;

import eu.cloudnetservice.wrapper.transform.Transformer;
import java.lang.reflect.Field;
import lombok.NonNull;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public class FAWEReflectionUtilsTransformer implements Transformer {

  public static void setFailsafeFieldValue(Field field, Object target, Object value)
    throws NoSuchFieldException, IllegalAccessException {
    field.setAccessible(true);
    Field modifiersField = Field.class.getDeclaredField("modifiers");
    modifiersField.setAccessible(true);
    int modifiers = modifiersField.getInt(field);
    modifiers &= 0xFFFFFFEF;
    modifiersField.setInt(field, modifiers);
    field.set(target, value);
  }

  @Override
  public void transform(@NonNull String classname, @NonNull ClassNode classNode) {
    for (var method : classNode.methods) {
      if (method.name.equals("setFailsafeFieldValue")) {
        for (var instruction : method.instructions) {
          if (instruction instanceof LdcInsnNode ldc && ldc.cst.equals("modifiers")) {
            var instructions = new InsnList();

            instructions.add(new VarInsnNode(ALOAD, 0));
            instructions.add(new InsnNode(ICONST_1));
            instructions.add(new MethodInsnNode(INVOKEVIRTUAL, getInternalName(Field.class), "setAccessible",
              getMethodDescriptor(VOID_TYPE, BOOLEAN_TYPE)));
            instructions.add(new VarInsnNode(ALOAD, 0));
            instructions.add(new VarInsnNode(ALOAD, 1));
            instructions.add(new VarInsnNode(ALOAD, 2));
            instructions.add(new MethodInsnNode(INVOKEVIRTUAL, getInternalName(Field.class), "set",
              getMethodDescriptor(VOID_TYPE, getType(Object.class), getType(Object.class))));
            instructions.add(new InsnNode(RETURN));

            method.instructions.insert(instructions);
          }
        }
      }
    }
  }
}
