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

package eu.cloudnetservice.node.impl.service.defaults;

import com.google.common.base.Preconditions;
import com.google.common.hash.Hashing;
import com.google.common.net.InetAddresses;
import eu.cloudnetservice.driver.channel.ChannelMessage;
import eu.cloudnetservice.driver.channel.ChannelMessageSender;
import eu.cloudnetservice.driver.channel.ChannelMessageTarget;
import eu.cloudnetservice.driver.document.Document;
import eu.cloudnetservice.driver.event.EventManager;
import eu.cloudnetservice.driver.impl.network.NetworkConstants;
import eu.cloudnetservice.driver.language.I18n;
import eu.cloudnetservice.driver.network.HostAndPort;
import eu.cloudnetservice.driver.network.NetworkChannel;
import eu.cloudnetservice.driver.network.buffer.DataBuf;
import eu.cloudnetservice.driver.network.ssl.SSLConfiguration;
import eu.cloudnetservice.driver.registry.Service;
import eu.cloudnetservice.driver.service.ProcessSnapshot;
import eu.cloudnetservice.driver.service.ServiceConfiguration;
import eu.cloudnetservice.driver.service.ServiceDeployment;
import eu.cloudnetservice.driver.service.ServiceEnvironmentType;
import eu.cloudnetservice.driver.service.ServiceId;
import eu.cloudnetservice.driver.service.ServiceInfoSnapshot;
import eu.cloudnetservice.driver.service.ServiceLifeCycle;
import eu.cloudnetservice.driver.service.ServiceRemoteInclusion;
import eu.cloudnetservice.driver.service.ServiceTask;
import eu.cloudnetservice.driver.service.ServiceTemplate;
import eu.cloudnetservice.node.config.Configuration;
import eu.cloudnetservice.node.event.service.CloudServiceCreateEvent;
import eu.cloudnetservice.node.event.service.CloudServiceDeploymentEvent;
import eu.cloudnetservice.node.event.service.CloudServicePostLifecycleEvent;
import eu.cloudnetservice.node.event.service.CloudServicePostPrepareEvent;
import eu.cloudnetservice.node.event.service.CloudServicePreLifecycleEvent;
import eu.cloudnetservice.node.event.service.CloudServicePreLoadInclusionEvent;
import eu.cloudnetservice.node.event.service.CloudServicePrePrepareEvent;
import eu.cloudnetservice.node.event.service.CloudServiceTemplateLoadEvent;
import eu.cloudnetservice.node.impl.service.InternalCloudService;
import eu.cloudnetservice.node.impl.service.InternalCloudServiceManager;
import eu.cloudnetservice.node.impl.tick.DefaultTickLoop;
import eu.cloudnetservice.node.impl.version.ServiceVersionProvider;
import eu.cloudnetservice.node.service.CloudServiceManager;
import eu.cloudnetservice.node.service.ServiceConfigurationPreparer;
import eu.cloudnetservice.node.service.ServiceConsoleLogCache;
import eu.cloudnetservice.utils.base.StringUtil;
import eu.cloudnetservice.utils.base.concurrent.TaskUtil;
import eu.cloudnetservice.utils.base.io.FileUtil;
import eu.cloudnetservice.utils.base.resource.CpuUsageResolver;
import io.vavr.Tuple2;
import java.net.Inet6Address;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;
import kong.unirest.core.Unirest;
import kong.unirest.core.UnirestException;
import lombok.NonNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractService implements InternalCloudService {

  protected static final Logger LOGGER = LoggerFactory.getLogger(AbstractService.class);

  protected static final Path INCLUSION_TEMP_DIR = FileUtil.TEMP_DIR.resolve("inclusions");
  protected static final Path WRAPPER_CONFIG_PATH = Path.of(".wrapper", "wrapper.json");
  protected static final BiPredicate<String, Pattern> FILE_MATCHER_PREDICATE =
    (fileName, pattern) -> pattern.matcher(fileName).matches();

  protected final String connectionKey;
  protected final Path pluginDirectory;
  protected final Path serviceDirectory;

  protected final I18n i18n;
  protected final DefaultTickLoop mainThread;
  protected final EventManager eventManager;
  protected final Configuration configuration;
  protected final InternalCloudServiceManager cloudServiceManager;
  protected final ServiceConfiguration serviceConfiguration;
  protected final ServiceVersionProvider serviceVersionProvider;
  protected final ServiceConfigurationPreparer serviceConfigurationPreparer;

  protected final Lock lifecycleLock = new ReentrantLock(true);
  protected final Set<Tuple2<ChannelMessageTarget, String>> logTargets = ConcurrentHashMap.newKeySet();

  protected final Queue<ServiceTemplate> waitingTemplates = new ConcurrentLinkedQueue<>();
  protected final Queue<ServiceDeployment> waitingDeployments = new ConcurrentLinkedQueue<>();
  protected final Queue<ServiceRemoteInclusion> waitingRemoteInclusions = new ConcurrentLinkedQueue<>();

  protected final Collection<ServiceTemplate> installedTemplates = ConcurrentHashMap.newKeySet();
  protected final Collection<ServiceRemoteInclusion> installedInclusions = ConcurrentHashMap.newKeySet();
  protected final Collection<ServiceDeployment> installedDeployments = ConcurrentHashMap.newKeySet();

  protected ServiceConsoleLogCache logCache;

  protected volatile NetworkChannel networkChannel;
  protected volatile long connectionTimestamp = -1;

  protected volatile ServiceInfoSnapshot lastServiceInfo;
  protected volatile ServiceInfoSnapshot currentServiceInfo;

  protected AbstractService(
    @NonNull @Service I18n i18n,
    @NonNull DefaultTickLoop tickLoop,
    @NonNull Configuration nodeConfig,
    @NonNull ServiceConfiguration configuration,
    @NonNull InternalCloudServiceManager manager,
    @NonNull EventManager eventManager,
    @NonNull ServiceVersionProvider versionProvider,
    @NonNull ServiceConfigurationPreparer serviceConfigurationPreparer
  ) {
    this.i18n = i18n;
    this.mainThread = tickLoop;
    this.configuration = nodeConfig;
    this.eventManager = eventManager;
    this.cloudServiceManager = manager;
    this.serviceConfiguration = configuration;
    this.serviceVersionProvider = versionProvider;
    this.serviceConfigurationPreparer = serviceConfigurationPreparer;

    this.connectionKey = StringUtil.generateRandomString(64);
    this.serviceDirectory = resolveServicePath(configuration.serviceId(), manager, configuration.staticService());
    this.pluginDirectory = this.serviceDirectory
      .resolve(configuration.serviceId().environment().readProperty(ServiceEnvironmentType.PLUGIN_DIR));

    this.currentServiceInfo = new ServiceInfoSnapshot(
      System.currentTimeMillis(),
      new HostAndPort(configuration.hostAddress(), configuration.port()),
      ProcessSnapshot.empty(),
      configuration,
      -1,
      ServiceLifeCycle.PREPARED,
      configuration.propertyHolder().immutableCopy());
    this.pushServiceInfoSnapshotUpdate(ServiceLifeCycle.PREPARED, false);

    // register the service locally for now
    manager.registerUnacceptedService(this);
  }

  protected static @NonNull Path resolveServicePath(
    @NonNull ServiceId serviceId,
    @NonNull CloudServiceManager manager,
    boolean staticService
  ) {
    // validate the service name
    if (!ServiceTask.NAMING_PATTERN.matcher(serviceId.name()).matches()) {
      throw new IllegalArgumentException(
        "Service name \"" + serviceId.name() + "\" must match pattern \"" + ServiceTask.NAMING_PATTERN + "\"");
    }
    // resolve the path of the service in the logical directory
    return staticService
      ? manager.persistentServicesDirectory().resolve(serviceId.name())
      : manager.tempDirectory().resolve(String.format("%s_%s", serviceId.name(), serviceId.uniqueId()));
  }

  @Override
  public @NonNull ServiceInfoSnapshot serviceInfo() {
    return this.currentServiceInfo;
  }

  @Override
  public boolean valid() {
    return this.currentServiceInfo.lifeCycle() != ServiceLifeCycle.DELETED;
  }

  @Override
  public @Nullable ServiceInfoSnapshot forceUpdateServiceInfo() {
    // check if the service is able to serve the request
    if (this.networkChannel != null) {
      var response = ChannelMessage.builder()
        .targetService(this.serviceId().name())
        .message("request_update_service_information")
        .channel(NetworkConstants.INTERNAL_MSG_CHANNEL)
        .build()
        .sendSingleQuery();
      return response == null ? this.currentServiceInfo : response.content().readObject(ServiceInfoSnapshot.class);
    } else {
      return this.currentServiceInfo;
    }
  }

  @Override
  public void addServiceTemplate(@NonNull ServiceTemplate serviceTemplate) {
    this.waitingTemplates.add(serviceTemplate);
  }

  @Override
  public void addServiceRemoteInclusion(@NonNull ServiceRemoteInclusion serviceRemoteInclusion) {
    this.waitingRemoteInclusions.add(serviceRemoteInclusion);
  }

  @Override
  public void addServiceDeployment(@NonNull ServiceDeployment serviceDeployment) {
    this.waitingDeployments.add(serviceDeployment);
  }

  @Override
  public @NonNull ServiceConsoleLogCache serviceConsoleLogCache() {
    return this.logCache;
  }

  @Override
  public void deleteFiles() {
    // stop the process & delete the configured files
    this.doDelete();
    // delete the folder of the service, even if it's a static service
    FileUtil.delete(this.serviceDirectory);
    // push the new lifecycle
    this.pushServiceInfoSnapshotUpdate(ServiceLifeCycle.DELETED);
  }

  @Override
  public void updateLifecycle(@NonNull ServiceLifeCycle lifeCycle) {
    this.updateLifecycle(lifeCycle, this.serviceConfiguration().autoDeleteOnStop());
  }

  @Override
  public @NonNull CompletableFuture<ServiceInfoSnapshot> serviceInfoAsync() {
    return TaskUtil.supplyAsync(this::serviceInfo);
  }

  @Override
  public @NonNull CompletableFuture<Boolean> validAsync() {
    return TaskUtil.supplyAsync(this::valid);
  }

  @Override
  public @NonNull CompletableFuture<ServiceInfoSnapshot> forceUpdateServiceInfoAsync() {
    return TaskUtil.supplyAsync(this::forceUpdateServiceInfo);
  }

  @Override
  public @NonNull CompletableFuture<Void> addServiceTemplateAsync(@NonNull ServiceTemplate serviceTemplate) {
    return TaskUtil.runAsync(() -> this.addServiceTemplate(serviceTemplate));
  }

  @Override
  public @NonNull CompletableFuture<Void> addServiceRemoteInclusionAsync(
    @NonNull ServiceRemoteInclusion serviceRemoteInclusion
  ) {
    return TaskUtil.runAsync(() -> this.addServiceRemoteInclusion(serviceRemoteInclusion));
  }

  @Override
  public @NonNull CompletableFuture<Void> addServiceDeploymentAsync(@NonNull ServiceDeployment serviceDeployment) {
    return TaskUtil.runAsync(() -> this.addServiceDeployment(serviceDeployment));
  }

  @Override
  public @NonNull CompletableFuture<Queue<String>> cachedLogMessagesAsync() {
    return TaskUtil.supplyAsync(this::cachedLogMessages);
  }

  @Override
  public @NonNull CompletableFuture<Boolean> toggleScreenEventsAsync(
    @NonNull ChannelMessageSender sender,
    @NonNull String channel
  ) {
    return TaskUtil.supplyAsync(() -> this.toggleScreenEvents(sender, channel));
  }

  @Override
  public @NonNull CompletableFuture<Void> restartAsync() {
    return TaskUtil.runAsync(this::restart);
  }

  @Override
  public @NonNull CompletableFuture<Void> updateLifecycleAsync(@NonNull ServiceLifeCycle lifeCycle) {
    return TaskUtil.runAsync(() -> this.updateLifecycle(lifeCycle));
  }

  @Override
  public @NonNull CompletableFuture<Void> deleteFilesAsync() {
    return TaskUtil.runAsync(this::deleteFiles);
  }

  @Override
  public @NonNull CompletableFuture<Void> runCommandAsync(@NonNull String command) {
    return TaskUtil.runAsync(() -> this.runCommand(command));
  }

  @Override
  public @NonNull CompletableFuture<Void> includeWaitingServiceTemplatesAsync() {
    return TaskUtil.runAsync(this::includeWaitingServiceTemplates);
  }

  @Override
  public @NonNull CompletableFuture<Void> includeWaitingServiceTemplatesAsync(boolean force) {
    return TaskUtil.runAsync(() -> this.includeWaitingServiceTemplates(force));
  }

  @Override
  public @NonNull CompletableFuture<Void> includeWaitingServiceInclusionsAsync() {
    return TaskUtil.runAsync(this::includeWaitingServiceInclusions);
  }

  @Override
  public @NonNull CompletableFuture<Void> deployResourcesAsync(boolean removeDeployments) {
    return TaskUtil.runAsync(() -> this.deployResources(removeDeployments));
  }

  @Override
  public @NonNull CompletableFuture<Void> removeAndExecuteDeploymentsAsync() {
    return TaskUtil.runAsync(this::removeAndExecuteDeployments);
  }

  @Override
  public @NonNull CompletableFuture<Void> updatePropertiesAsync(@NonNull Document properties) {
    return TaskUtil.runAsync(() -> this.updateProperties(properties));
  }

  protected void updateLifecycle(@NonNull ServiceLifeCycle lifeCycle, boolean switchToDeletedOnStop) {
    try {
      // prevent multiple service updates at the same time
      this.lifecycleLock.lock();
      // prevent changing the lifecycle to an incompatible lifecycle
      if (!this.lifeCycle().canChangeTo(lifeCycle)) {
        return;
      }
      // select the appropriate method for the lifecycle
      switch (lifeCycle) {
        case DELETED -> {
          if (this.preLifecycleChange(ServiceLifeCycle.DELETED)) {
            this.doDelete();
            // update the current service info
            this.pushServiceInfoSnapshotUpdate(ServiceLifeCycle.DELETED);
            LOGGER.info(this.i18n.translate("cloudnet-service-post-delete-message", this.serviceReplacement()));
          }
        }

        case RUNNING -> {
          if (this.preLifecycleChange(ServiceLifeCycle.RUNNING)) {
            // check if we can start the process now
            if (this.lifeCycle() == ServiceLifeCycle.PREPARED && this.canStartNow()) {
              this.prepareService();
              this.startProcess();
              // update the current service info
              this.pushServiceInfoSnapshotUpdate(ServiceLifeCycle.RUNNING);
              LOGGER.info(this.i18n.translate("cloudnet-service-post-start-message", this.serviceReplacement()));
            }
          }
        }

        case STOPPED -> {
          if (this.preLifecycleChange(ServiceLifeCycle.STOPPED)) {
            // check if we should delete the service when stopping
            if (switchToDeletedOnStop) {
              this.doDelete();
              // update the current service info
              this.pushServiceInfoSnapshotUpdate(ServiceLifeCycle.DELETED);
              LOGGER.info(this.i18n.translate("cloudnet-service-post-stop-message", this.serviceReplacement()));
            } else if (this.lifeCycle() == ServiceLifeCycle.RUNNING) {
              this.stopProcess();
              this.doRemoveFilesAfterStop();
              // reset the service lifecycle to prepared
              this.pushServiceInfoSnapshotUpdate(ServiceLifeCycle.PREPARED);
            }
          }
        }
        // cannot be set - just ignore
        case PREPARED -> {
          // in this state no templates, inclusions or deployments are installed anymore
          this.installedTemplates.clear();
          this.installedInclusions.clear();
          this.installedDeployments.clear();

          LOGGER.info(this.i18n.translate("cloudnet-service-post-prepared-message", this.serviceReplacement()));
        }
        default -> throw new IllegalStateException("Unhandled ServiceLifeCycle: " + lifeCycle);
      }
    } finally {
      this.lifecycleLock.unlock();
    }
  }

  @Override
  public void restart() {
    this.updateLifecycle(ServiceLifeCycle.STOPPED, false);
    this.updateLifecycle(ServiceLifeCycle.RUNNING);
  }

  @Override
  public @NonNull Collection<ServiceTemplate> installedTemplates() {
    return this.installedTemplates;
  }

  @Override
  public @NonNull Collection<ServiceRemoteInclusion> installedInclusions() {
    return this.installedInclusions;
  }

  @Override
  public @NonNull Collection<ServiceDeployment> installedDeployments() {
    return this.installedDeployments;
  }

  @Override
  public void includeWaitingServiceTemplates() {
    this.includeWaitingServiceTemplates(true);
  }

  @Override
  public void includeWaitingServiceTemplates(boolean force) {
    this.waitingTemplates.stream()
      .filter(template -> {
        // always allow manual requests & non-static service copies
        if (force || !this.serviceConfiguration().staticService()) {
          return true;
        }
        // only allow this template to be copied if explicitly defined
        return template.alwaysCopyToStaticServices();
      })
      .sorted()
      .forEachOrdered(template -> {
        // remove the entry
        this.waitingTemplates.remove(template);
        // check if we should load the template
        var storage = template.storage();
        if (!this.eventManager.callEvent(new CloudServiceTemplateLoadEvent(this, storage, template)).cancelled()) {
          // the event is not cancelled - copy the template
          storage.pull(template, this.serviceDirectory);
          // we've pulled the template
          this.installedTemplates.add(template);
        }
      });
  }

  @Override
  public void includeWaitingServiceInclusions() {
    ServiceRemoteInclusion inclusion;
    while ((inclusion = this.waitingRemoteInclusions.poll()) != null) {
      // check if we should load the inclusion
      var preLoadEvent = this.eventManager.callEvent(new CloudServicePreLoadInclusionEvent(this, inclusion));
      if (!preLoadEvent.cancelled()) {
        // the event might have changed the inclusion, use the updated one
        inclusion = preLoadEvent.inclusion();
        // resolve the desired output path
        var target = this.serviceDirectory.resolve(inclusion.destination());
        FileUtil.ensureChild(this.serviceDirectory, target);

        try {
          if (inclusion.cacheStrategy().equals(ServiceRemoteInclusion.KEEP_UNTIL_RESTART_STRATEGY)) {
            // get a target path based on the download url
            var encodedUrl = Hashing.murmur3_128().hashString(inclusion.url(), StandardCharsets.UTF_8).toString();
            var destination = INCLUSION_TEMP_DIR.resolve(encodedUrl);
            // download the file to the temp path if it does not exist
            if (Files.notExists(destination)) {
              this.downloadInclusionFile(inclusion, destination);
            }

            // copy the file from the temp path to the desired output path
            FileUtil.copy(destination, target);
          } else {
            // download the file directly to the target path if caching is disabled
            this.downloadInclusionFile(inclusion, target);
          }

          // we've installed the inclusion successfully
          this.installedInclusions.add(inclusion);
        } catch (UnirestException exception) {
          LOGGER.warn(
            "Unable to download inclusion from {} to {}",
            inclusion.url(),
            target,
            exception.getCause());
        }
      }
    }
  }

  @Override
  public void deployResources(boolean removeDeployments) {
    if (removeDeployments) {
      // remove all deployments while execute the deployments
      ServiceDeployment deployment;
      while ((deployment = this.waitingDeployments.poll()) != null) {
        this.executeDeployment(deployment);
      }
    } else {
      // just execute all deployments
      for (var deployment : this.waitingDeployments) {
        this.executeDeployment(deployment);
      }
    }
  }

  @Override
  public void updateProperties(@NonNull Document properties) {
    // check if the service is able to serve the request
    if (this.networkChannel != null) {
      ChannelMessage.builder()
        .targetService(this.serviceId().name())
        .channel(NetworkConstants.INTERNAL_MSG_CHANNEL)
        .message("request_update_service_information_with_new_properties")
        .buffer(DataBuf.empty().writeObject(properties))
        .build()
        .send();
      return;
    }

    // not connected - just update
    this.pushServiceInfoSnapshotUpdate(this.lastServiceInfo.lifeCycle(), properties, true);
  }

  protected void doDelete() {
    // stop the process if it's running
    if (this.currentServiceInfo.lifeCycle() == ServiceLifeCycle.RUNNING || this.alive()) {
      this.stopProcess();
    }
    // execute all deployments which are still waiting - delete all requested files before that
    this.doRemoveFilesAfterStop();
    this.removeAndExecuteDeployments();
    // remove the current directory if the service is not static
    if (!this.serviceConfiguration().staticService()) {
      FileUtil.delete(this.serviceDirectory);
    }
  }

  @Override
  public @NonNull Queue<ServiceRemoteInclusion> waitingIncludes() {
    return this.waitingRemoteInclusions;
  }

  @Override
  public @NonNull Queue<ServiceTemplate> waitingTemplates() {
    return this.waitingTemplates;
  }

  @Override
  public @NonNull Queue<ServiceDeployment> waitingDeployments() {
    return this.waitingDeployments;
  }

  @Override
  public @NonNull ServiceLifeCycle lifeCycle() {
    return this.currentServiceInfo.lifeCycle();
  }

  @Override
  public @NonNull CloudServiceManager cloudServiceManager() {
    return this.cloudServiceManager;
  }

  @Override
  public @NonNull ServiceConfiguration serviceConfiguration() {
    return this.currentServiceInfo.configuration();
  }

  @Override
  public @NonNull ServiceId serviceId() {
    return this.currentServiceInfo.serviceId();
  }

  @Override
  public @NonNull String connectionKey() {
    return this.connectionKey;
  }

  @Override
  public @NonNull Path directory() {
    return this.serviceDirectory;
  }

  @Override
  public @NonNull Path pluginDirectory() {
    return this.pluginDirectory;
  }

  @Override
  public @Nullable NetworkChannel networkChannel() {
    return this.networkChannel;
  }

  @Override
  public void networkChannel(@Nullable NetworkChannel channel) {
    Preconditions.checkArgument(this.networkChannel == null || channel == null);
    // close the channel if the new channel is null
    if (this.networkChannel != null) {
      this.networkChannel.close();
      this.connectionTimestamp = -1;
    } else {
      this.connectionTimestamp = System.currentTimeMillis();
    }
    // set the new channel
    this.networkChannel = channel;
    this.pushServiceInfoSnapshotUpdate(this.currentServiceInfo.lifeCycle(), false);
  }

  @Override
  public @NonNull ServiceInfoSnapshot lastServiceInfoSnapshot() {
    return this.lastServiceInfo;
  }

  @Override
  public void publishServiceInfoSnapshot() {
    ChannelMessage.builder()
      .targetAll()
      .message("update_service_info")
      .channel(NetworkConstants.INTERNAL_MSG_CHANNEL)
      .buffer(DataBuf.empty().writeObject(this.currentServiceInfo))
      .build()
      .send();
  }

  @Override
  public void handleServiceRegister() {
    // just ensure that this service is removed from the cache & moved to a "real" registered local service
    this.cloudServiceManager.registerLocalService(this);
    this.cloudServiceManager.takeUnacceptedService(this.serviceId().uniqueId());

    // publish the initial service info to the cluster
    this.pushServiceInfoSnapshotUpdate(ServiceLifeCycle.PREPARED);

    // notify the local listeners that this service was created
    this.eventManager.callEvent(new CloudServiceCreateEvent(this));
  }

  @Override
  public void updateServiceInfoSnapshot(@NonNull ServiceInfoSnapshot serviceInfoSnapshot) {
    this.lastServiceInfo = this.currentServiceInfo;
    this.currentServiceInfo = serviceInfoSnapshot;
  }

  @Override
  public @NonNull Queue<String> cachedLogMessages() {
    return this.serviceConsoleLogCache().cachedLogMessages();
  }

  @Override
  public boolean toggleScreenEvents(@NonNull ChannelMessageSender channelMessageSender, @NonNull String channel) {
    var pair = new Tuple2<>(channelMessageSender.toTarget(), channel);
    if (this.logTargets.remove(pair)) {
      return false;
    }
    // this returns always true, just to inline it.
    return this.logTargets.add(pair);
  }

  protected void executeDeployment(@NonNull ServiceDeployment deployment) {
    // check if we should execute the deployment
    var storage = deployment.template().storage();
    if (!this.eventManager.callEvent(new CloudServiceDeploymentEvent(this, storage, deployment)).cancelled()) {
      // execute the deployment
      storage.deployDirectory(deployment.template(), this.serviceDirectory, path -> {
        // normalize the name of the path
        var fileName = this.relativizePath(path);

        // check if we have any exclusions and the path matches one of them -> exclude the file
        var excludes = deployment.excludes();
        if (!excludes.isEmpty() && excludes.stream().anyMatch(input -> FILE_MATCHER_PREDICATE.test(fileName, input))) {
          return false;
        }

        // check if the includes are empty or the file is included explicitly -> include the file
        var includes = deployment.includes();
        return includes.isEmpty() || includes.stream().anyMatch(input -> FILE_MATCHER_PREDICATE.test(fileName, input));
      });
      // we've executed the deployment
      this.installedDeployments.add(deployment);
    }
  }

  protected @NonNull String relativizePath(@NonNull Path input) {
    // ensures that we get a file name which is equivalent on all operating systems
    var fileName = this.serviceDirectory.relativize(input).toString().replace('\\', '/');
    if (Files.isDirectory(input) && !fileName.endsWith("/")) {
      fileName += '/';
    }

    return fileName;
  }

  protected void doRemoveFilesAfterStop() {
    for (var file : this.serviceConfiguration.deletedFilesAfterStop()) {
      // ensure that nobody deletes files outside the service directory
      var filePath = this.serviceDirectory.resolve(file);
      FileUtil.ensureChild(this.serviceDirectory, filePath);

      // save to delete now
      FileUtil.delete(filePath);
    }
  }

  protected boolean preLifecycleChange(@NonNull ServiceLifeCycle targetLifecycle) {
    return !this.eventManager.callEvent(new CloudServicePreLifecycleEvent(this, targetLifecycle)).cancelled();
  }

  protected void pushServiceInfoSnapshotUpdate(@NonNull ServiceLifeCycle lifeCycle) {
    this.pushServiceInfoSnapshotUpdate(lifeCycle, true);
  }

  protected void pushServiceInfoSnapshotUpdate(@NonNull ServiceLifeCycle lifeCycle, boolean sendUpdate) {
    this.pushServiceInfoSnapshotUpdate(lifeCycle, null, sendUpdate);
  }

  protected void pushServiceInfoSnapshotUpdate(
    @NonNull ServiceLifeCycle lifeCycle,
    @Nullable Document properties,
    boolean sendUpdate
  ) {
    // save the current service info
    this.lastServiceInfo = this.currentServiceInfo;
    // update the current info
    this.currentServiceInfo = new ServiceInfoSnapshot(
      this.lastServiceInfo.creationTime(),
      this.lastServiceInfo.address(),
      this.alive() ? this.lastServiceInfo.processSnapshot() : ProcessSnapshot.empty(),
      this.lastServiceInfo.configuration(),
      this.connectionTimestamp,
      lifeCycle,
      Objects.requireNonNullElse(properties, this.lastServiceInfo.propertyHolder()));
    // remove the service in the local manager if the service was deleted
    if (lifeCycle == ServiceLifeCycle.DELETED) {
      this.cloudServiceManager.unregisterLocalService(this);
    }

    if (sendUpdate) {
      // call the lifecycle change event
      this.eventManager.callEvent(new CloudServicePostLifecycleEvent(this, lifeCycle));
      // publish the change to all services and nodes
      ChannelMessage.builder()
        .targetAll()
        .message("update_service_lifecycle")
        .channel(NetworkConstants.INTERNAL_MSG_CHANNEL)
        .buffer(DataBuf.empty().writeObject(this.lastServiceInfo.lifeCycle()).writeObject(this.currentServiceInfo))
        .build()
        .send();
    }
  }

  protected boolean canStartNow() {
    // check jvm heap size
    if (this.cloudServiceManager.currentUsedHeapMemory()
      + this.serviceConfiguration().processConfig().maxHeapMemorySize()
      > this.configuration.maxMemory()) {
      // schedule a retry
      if (this.configuration.runBlockedServiceStartTryLaterAutomatic()) {
        this.mainThread.runTask(this::start);
      } else {
        LOGGER.info(this.i18n.translate("cloudnet-service-manager-max-memory-error"));
      }
      // no starting now
      return false;
    }
    // check for cpu usage
    if (CpuUsageResolver.systemCpuLoad() >= this.configuration.maxCPUUsageToStartServices()) {
      // schedule a retry
      if (this.configuration.runBlockedServiceStartTryLaterAutomatic()) {
        this.mainThread.runTask(this::start);
      } else {
        LOGGER.info(this.i18n.translate("cloudnet-service-manager-cpu-usage-to-high-error"));
      }
      // no starting now
      return false;
    }
    // ok to start now
    return true;
  }

  protected void prepareService() {
    // initialize the service directory
    var firstStartup = Files.notExists(this.serviceDirectory);
    FileUtil.createDirectory(this.serviceDirectory);
    FileUtil.createDirectory(this.pluginDirectory);

    // load the ssl configuration if enabled
    var sslConfiguration = this.configuration.serverSSLConfig();
    var wrapperSslConfigDocument = this.prepareSslConfiguration(sslConfiguration);

    // add all components
    this.waitingTemplates.addAll(this.serviceConfiguration.templates());
    this.waitingDeployments.addAll(this.serviceConfiguration.deployments());
    this.waitingRemoteInclusions.addAll(this.serviceConfiguration.inclusions());

    // initial service details are now ready, let the modules know that we're starting to prepare
    this.eventManager.callEvent(new CloudServicePrePrepareEvent(this));

    // load the inclusions
    this.includeWaitingServiceInclusions();
    // check if we should load the templates of the service
    this.includeWaitingServiceTemplates(firstStartup);
    // update the service configuration
    this.serviceConfigurationPreparer.configure(this);
    // write the configuration file for the service
    var listener = this.selectConnectListener(this.configuration.identity().listeners());
    Document.newJsonDocument()
      .append("targetListener", listener)
      .append("connectionKey", this.connectionKey())
      .append("serviceInfoSnapshot", this.currentServiceInfo)
      .append("serviceConfiguration", this.serviceConfiguration())
      .append("sslConfiguration", wrapperSslConfigDocument)
      .writeTo(this.serviceDirectory.resolve(WRAPPER_CONFIG_PATH));
    // finished the prepare process
    this.eventManager.callEvent(new CloudServicePostPrepareEvent(this));
  }

  protected @NonNull HostAndPort selectConnectListener(@NonNull List<HostAndPort> listeners) {
    // select a listener for the service to connect to, randomly
    var listener = listeners.get(ThreadLocalRandom.current().nextInt(listeners.size()));
    // rewrite 0.0.0.0 to 127.0.0.1 (or ::0 to ::1) to prevent unexpected connection issues (wrapper to node connection)
    // if InetAddresses.forString throws an exception that is OK as the connection will fail anyway then
    var address = InetAddresses.forString(listener.host());
    if (address.isAnyLocalAddress()) {
      // rewrites ipv6 to an ipv6 local address
      return address instanceof Inet6Address
        ? new HostAndPort("::1", listener.port())
        : new HostAndPort("127.0.0.1", listener.port());
    } else {
      // no need to change anything
      return listener;
    }
  }

  protected @NonNull Document prepareSslConfiguration(@NonNull SSLConfiguration configuration) {
    // in case the trust certificate collection file path is not present, just put the enabled status
    // into the file as the wrapper needs to use the insecure trust factory anyway.
    var trustCertificateCollectionPath = configuration.trustCertificatePath();
    if (!configuration.enabled()
      || trustCertificateCollectionPath == null
      || !Files.isRegularFile(trustCertificateCollectionPath)) {
      return Document.newJsonDocument().append("enabled", configuration.enabled());
    }

    // copy the trust certificate collection file into the wrapper directory
    var wrapperDir = this.serviceDirectory.resolve(".wrapper");
    var wrapperTrustCertificateCollectionPath = wrapperDir.resolve("trustcertificatecollection.crt");
    FileUtil.copy(trustCertificateCollectionPath, wrapperTrustCertificateCollectionPath);

    // append the file location into ssl configuration document as a relative path
    var relativeFilePath = wrapperDir.relativize(wrapperTrustCertificateCollectionPath);
    return Document.newJsonDocument()
      .append("enabled", true)
      .append("trustCertificatePath", relativeFilePath.toString());
  }

  protected void downloadInclusionFile(@NonNull ServiceRemoteInclusion inclusion, @NonNull Path destination) {
    // prepare the connection from which we load the inclusion
    var request = Unirest.get(inclusion.url());
    // put the given http headers
    var headers = inclusion.readPropertyOrDefault(ServiceRemoteInclusion.HEADERS, Map.of());
    for (var entry : headers.entrySet()) {
      request.header(entry.getKey(), entry.getValue());
    }

    FileUtil.createDirectory(destination.getParent());
    request.asFile(destination.toString(), StandardCopyOption.REPLACE_EXISTING);
  }

  protected @NonNull Object[] serviceReplacement() {
    return new Object[]{
      this.serviceId().uniqueId(),
      this.serviceId().taskName(),
      this.serviceId().name(),
      this.serviceId().nodeUniqueId()};
  }

  protected abstract void startProcess();

  protected abstract void stopProcess();
}
