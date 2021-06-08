/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.gateway.filter;

import java.net.URI;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Mono;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.gateway.config.LoadBalancerProperties;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.*;

/**
 * @author Spencer Gibb
 * @author Tim Ysewyn
 */
public class LoadBalancerClientFilter implements GlobalFilter, Ordered {

	/**
	 * Filter order for {@link LoadBalancerClientFilter}.
	 */
	public static final int LOAD_BALANCER_CLIENT_FILTER_ORDER = 10100;

	private static final Log log = LogFactory.getLog(LoadBalancerClientFilter.class);

	protected final LoadBalancerClient loadBalancer;

	private LoadBalancerProperties properties;

	public LoadBalancerClientFilter(LoadBalancerClient loadBalancer,
			LoadBalancerProperties properties) {
		this.loadBalancer = loadBalancer;
		this.properties = properties;
	}

	@Override
	public int getOrder() {
		return LOAD_BALANCER_CLIENT_FILTER_ORDER;
	}

	@Override
	@SuppressWarnings("Duplicates")
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		URI url = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);

		//url=lb://customer:8083/test1/method2
		log.info("我是LoadBalancerClientFilter的，我被调用了，url=" + url);

		String schemePrefix = exchange.getAttribute(GATEWAY_SCHEME_PREFIX_ATTR);
		if (url == null
				|| (!"lb".equals(url.getScheme()) && !"lb".equals(schemePrefix))
				|| (!"dubbo".equals(url.getScheme()) && !"dubbo".equals(schemePrefix))) {
			return chain.filter(exchange);
		}
		// preserve the original url
		addOriginalRequestUrl(exchange, url);

		log.trace("LoadBalancerClientFilter url before: " + url);

		// 加入dubbo调用特殊判断
		if("dubbo".equals(url.getScheme())) {
			String serviceName = url.getHost();
			String serviceUri = url.getPath();

			exchange.getAttributes().put(DUBBO_SERVICE_NAME_ATTR,
					serviceName);
			exchange.getAttributes().put(DUBBO_SERVICE_URI_ATTR,
					serviceUri);

			return chain.filter(exchange);
		}

		final ServiceInstance instance = choose(exchange);

		if (instance == null) {
			throw NotFoundException.create(properties.isUse404(),
					"Unable to find instance for " + url.getHost());
		}

		URI uri = exchange.getRequest().getURI();

		// if the `lb:<scheme>` mechanism was used, use `<scheme>` as the default,
		// if the loadbalancer doesn't provide one.
		String overrideScheme = instance.isSecure() ? "https" : "http";
		if (schemePrefix != null) {
			overrideScheme = url.getScheme();
		}

		URI requestUrl = loadBalancer.reconstructURI(
				new DelegatingServiceInstance(instance, overrideScheme), uri);

		log.trace("LoadBalancerClientFilter url chosen: " + requestUrl);
		exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, requestUrl);
		return chain.filter(exchange);
	}

	protected ServiceInstance choose(ServerWebExchange exchange) {
		return loadBalancer.choose(
				((URI) exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR)).getHost());
	}

	class DelegatingServiceInstance implements ServiceInstance {

		final ServiceInstance delegate;

		private String overrideScheme;

		DelegatingServiceInstance(ServiceInstance delegate, String overrideScheme) {
			this.delegate = delegate;
			this.overrideScheme = overrideScheme;
		}

		@Override
		public String getServiceId() {
			return delegate.getServiceId();
		}

		@Override
		public String getHost() {
			return delegate.getHost();
		}

		@Override
		public int getPort() {
			return delegate.getPort();
		}

		@Override
		public boolean isSecure() {
			return delegate.isSecure();
		}

		@Override
		public URI getUri() {
			return delegate.getUri();
		}

		@Override
		public Map<String, String> getMetadata() {
			return delegate.getMetadata();
		}

		@Override
		public String getScheme() {
			String scheme = delegate.getScheme();
			if (scheme != null) {
				return scheme;
			}
			return this.overrideScheme;
		}

	}

}
