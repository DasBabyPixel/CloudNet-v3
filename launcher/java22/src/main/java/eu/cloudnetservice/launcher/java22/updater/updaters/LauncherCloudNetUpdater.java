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

package eu.cloudnetservice.launcher.java22.updater.updaters;

import eu.cloudnetservice.ext.updater.Updater;
import eu.cloudnetservice.ext.updater.util.GitHubUtil;
import eu.cloudnetservice.launcher.java22.updater.LauncherUpdaterContext;
import eu.cloudnetservice.launcher.java22.updater.util.FileDownloadUpdateHelper;
import lombok.NonNull;

public final class LauncherCloudNetUpdater implements Updater<LauncherUpdaterContext> {

  @Override
  public void executeUpdates(@NonNull LauncherUpdaterContext context, boolean onlyIfRequired) throws Exception {
    var cloudNetChecksum = context.checksums().getProperty("node");
    var cloudNetFilePath = context.launcher().workingDirectory().resolve("cloudnet.jar");
    var downloadUri = GitHubUtil.buildUri(context.repo(), context.branch(), "node.jar");
    // download the new node file
    FileDownloadUpdateHelper.updateFile(downloadUri, cloudNetFilePath, cloudNetChecksum, "node", onlyIfRequired);
  }
}
