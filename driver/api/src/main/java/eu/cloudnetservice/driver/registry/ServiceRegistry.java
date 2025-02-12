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

package eu.cloudnetservice.driver.registry;

import java.util.Collection;
import lombok.NonNull;
import org.jetbrains.annotations.UnknownNullability;
import org.jetbrains.annotations.UnmodifiableView;

/**
 * The service registry manages and provides access to service providers. Services are interfaces which define a set of
 * methods a provider of a service must implement. Modules and/or plugins can query implementations from the registry
 * (if one is available for the given service). If multiple service providers are present for a service the caller must
 * explicitly select one by their name or use the provider which was marked as the default for the service.
 *
 * @since 4.0
 */
public interface ServiceRegistry {

  /**
   * Get the jvm-static singleton instance of the service registry. The returned instance is the same as retrievable
   * from injection. If available callers should opt for using dependency injection instead.
   *
   * @return the jvm-static singleton instance of the service registry.
   */
  static @NonNull ServiceRegistry registry() {
    return ServiceRegistryHolder.instance();
  }

  /**
   * Discovers and registers all auto service definitions which were generated by the annotation processor and that are
   * available from the code source of the given owner class. The method does the following steps to resolve the service
   * definitions:
   * <ol>
   *   <li>Resolve the code source of the given owner class (e.g. the containing jar file)
   *   <li>Resolve the {@code autoservices} directory in the resolved code source.
   *   <li>Visit all files in that directory and register the deserialized bindings from the file.
   * </ol>
   * <p>
   * The class loader of the given owner class is used to resolve class references, for example the implementation
   * types. When a non-singleton service instance needs to be constructed, the injection layer of the given owner class
   * will be used to construct the instance. If a different behaviour is needed the caller should not use auto service
   * registration and register the services manually instead.
   *
   * @param owner a class from the owning class source of the auto service bindings to register.
   * @throws NullPointerException  if the given owner class is null.
   * @throws IllegalStateException if the auto service mappings cannot be deserialized.
   */
  void discoverServices(@NonNull Class<?> owner);

  /**
   * Registers a singleton service into this service registry. Each retrieval of the service will return an accessor to
   * the given singleton instance to call methods on. If another service implementation with the same name is already
   * registered, this method invocation will be ignored and the registration of the already registered provider is
   * returned instead.
   *
   * @param serviceType           the type of the service with which the implementation should be associated.
   * @param serviceName           the name of the implementation for later retrievals.
   * @param serviceImplementation the implementation instance of the service to bind to.
   * @param <S>                   the service type model.
   * @return a registration to the newly or already registered service registration.
   * @throws NullPointerException     if the service type, name or implementation is null.
   * @throws IllegalArgumentException if the service type is not an interface, the impl is not extending the service
   *                                  type or the service name is empty.
   */
  @NonNull
  <S> ServiceRegistryRegistration<S> registerProvider(
    @NonNull Class<S> serviceType,
    @NonNull String serviceName,
    @NonNull S serviceImplementation);

  /**
   * Registers a service into this service registry which will return a new instance on each invocation, using the
   * public no-arg constructor in the given implementation type. If another service implementation with the same name is
   * already registered, this method invocation will be ignored and the registration of the already registered provider
   * is returned instead.
   *
   * @param serviceType        the type of the service with which the implementation should be associated.
   * @param serviceName        the name of the implementation for later retrievals.
   * @param implementationType the implementation type to constructor for service instances.
   * @param <S>                the service type model.
   * @return a registration to the newly or already registered service registration.
   * @throws NullPointerException     if the service type, name or implementation type is null.
   * @throws IllegalArgumentException if the service type is not an interface, the impl type is not extending the
   *                                  service type, the service name is empty or the impl type does not contain a
   *                                  public, accessible no-arg constructor.
   */
  @NonNull
  <S> ServiceRegistryRegistration<S> registerConstructingProvider(
    @NonNull Class<S> serviceType,
    @NonNull String serviceName,
    @NonNull Class<? extends S> implementationType);

  /**
   * Unregisters all service registrations from this registry whose
   * <ol>
   *   <li>service type uses the given class loader.
   *   <li>implementation type uses the given class loader.
   * </ol>
   *
   * @param classLoader the class loader of which all associated registrations should be removed.
   * @throws NullPointerException if the given class loader is null.
   */
  void unregisterAll(@NonNull ClassLoader classLoader);

  /**
   * Get all service types for which an implementation was registered. Updates to the underlying collection are
   * reflected into the returned collection, but no changes can be made to the returned collection.
   *
   * @return all service types for which an implementation was registered.
   */
  @NonNull
  @UnmodifiableView
  Collection<Class<?>> registeredServiceTypes();

  /**
   * Get the registration of a service implementation based on the given service type and name.
   *
   * @param service the service type to retrieve an implementation of.
   * @param name    the name of the implementation to get.
   * @param <S>     the service type model.
   * @return the registration of the implementation for the service type and name, null if no such registration exists.
   * @throws NullPointerException if the given service type or name is null.
   */
  @UnknownNullability
  <S> ServiceRegistryRegistration<S> registration(@NonNull Class<S> service, @NonNull String name);

  /**
   * Get the default registration for the given service in this service registry. The implementation might return a
   * proxy which always returns information about the current default implementation instead of the actual default
   * implementation registration.
   *
   * @param service the service to the default implementation registration of.
   * @param <S>     the service type model.
   * @return the registration of the default impl for the given service or null if no impl for the service is present.
   * @throws NullPointerException if the given service type is null.
   */
  @UnknownNullability
  <S> ServiceRegistryRegistration<S> defaultRegistration(@NonNull Class<S> service);

  /**
   * Get a view of all registrations which are present for the given service type. Updates to the underlying collection
   * are reflected into the returned collection, but no changes can be made to the returned collection. This rule does
   * not apply if no implementations for the service type is present in which case the implementation might return an
   * empty collection that will not see any updates on registrations.
   *
   * @param service the service to get the available registrations of.
   * @param <S>     the service type model.
   * @return a collection of all registrations for the given service type.
   * @throws NullPointerException if the given service type is null.
   */
  @NonNull
  @UnmodifiableView
  <S> Collection<ServiceRegistryRegistration<S>> registrations(@NonNull Class<S> service);

  /**
   * Get the instance of a service implementation based on the given service type and name.
   *
   * @param service the service type to retrieve the implementation of.
   * @param name    the name of the implementation to get.
   * @param <S>     the service type model.
   * @return the instance of the implementation for the service type and name, null if no such registration exists.
   * @throws NullPointerException if the given service type or name is null.
   */
  @UnknownNullability
  default <S> S instance(@NonNull Class<S> service, @NonNull String name) {
    var registration = this.registration(service, name);
    return registration != null ? registration.serviceInstance() : null;
  }

  /**
   * Get the instance of the default service implementation for the given service type. The implementation might decide
   * to return a proxy so that each invocation on the returned instance will always happen on the current default
   * implementation, even if the default changes during the lifetime of the returned instance.
   *
   * @param service the service type to retrieve the default implementation of.
   * @param <S>     the service type model.
   * @return the instance of the default implementation for the service type, null if no such registration exists.
   * @throws NullPointerException if the given service type is null.
   */
  @UnknownNullability
  default <S> S defaultInstance(@NonNull Class<S> service) {
    var registration = this.defaultRegistration(service);
    return registration != null ? registration.serviceInstance() : null;
  }
}
