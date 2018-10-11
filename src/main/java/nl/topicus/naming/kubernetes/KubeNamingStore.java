/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package nl.topicus.naming.kubernetes;

import java.util.Hashtable;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import javax.naming.Name;
import javax.naming.NamingException;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1ConfigMap;
import io.kubernetes.client.models.V1ConfigMapList;
import io.kubernetes.client.models.V1Secret;
import io.kubernetes.client.models.V1SecretList;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;

public class KubeNamingStore
{
	public static final String NAMESPACE_PROPERTY = "java.naming.kubernetes.namespace";

	private static final Logger logger = Logger.getLogger(KubeNamingStore.class);

	private static final String LABEL_SELECTOR = "k8s.naming.topicus.nl=true";

	private static final String ANNOTATION_KEY = "k8s.naming.topicus.nl/context";

	private final ApiClient client;

	private final CoreV1Api api;

	private final LoadingCache<Name, Optional<Object>> values;

	private final String namespace;

	public KubeNamingStore(final ApiClient client, final Hashtable<String, Object> envprops)
	{
		this.client = client;
		namespace = (String) envprops.getOrDefault(NAMESPACE_PROPERTY, "default");
		api = new CoreV1Api();
		values = createCache();
	}

	private LoadingCache<Name, Optional<Object>> createCache()
	{
		return CacheBuilder.newBuilder()
			.expireAfterWrite(24, TimeUnit.HOURS)
			.refreshAfterWrite(15, TimeUnit.MINUTES)
			.build(new CacheLoader<Name, Optional<Object>>()
			{
				@Override
				public Optional<Object> load(Name name) throws Exception
				{
					return Optional.ofNullable(loadInternal(name));
				}
			});
		// TODO : async refresh / k8s watch
	}

	private Object loadInternal(final Name name) throws ApiException
	{
		logger.debugv("Lookup for ''{0}'' in Kubernetes naming context...", name.toString());
		String context = getContext(name);
		String key = getKeyWithoutContext(name);
		Object result = loadFromConfigMap(context, key);
		return result == null ? loadFromSecret(context, key) : result;
	}

	private Object loadFromConfigMap(final String context, final String key) throws ApiException
	{
		V1ConfigMapList configMaps = api.listNamespacedConfigMap(namespace, null, null, null, null,
			LABEL_SELECTOR, null, null, null, null);
		for (V1ConfigMap configMap : configMaps.getItems())
		{
			String ann = null;
			if (configMap.getMetadata().getAnnotations() != null)
			{
				ann = configMap.getMetadata().getAnnotations().get(ANNOTATION_KEY);
			}

			if ((context == null && ann != null) || (context != null && ann == null)
				|| (context != null && ann != null && !context.equalsIgnoreCase(ann)))
			{
				logger.debugv(
					"Skipping configmap ''{3}'' (annotation {2}) for key ''{1}'' (context ''{0}'')",
					context, key, ann, configMap.getMetadata().getName());
				continue;
			}

			String value = configMap.getData().get(key);
			if (value != null)
			{
				logger.debugv(
					"Found value ''{0}'' for key ''{1}'' (context ''{2}'') in configmap ''{3}''",
					value, key, context != null ? context : "/", configMap.getMetadata().getName());
				return convertToJavaType(value);
			}
		}
		logger.debugv("Key ''{0}'' (context {1}) not found in configmap", key, context);
		return null;
	}

	private Object convertToJavaType(String value)
	{
		if (Boolean.TRUE.toString().equalsIgnoreCase(value))
		{
			return Boolean.TRUE;
		}
		if (Boolean.FALSE.toString().equalsIgnoreCase(value))
		{
			return Boolean.FALSE;
		}

		// FIXME: no negative numbers and everything is an int
		if (StringUtils.isNumeric(value))
		{
			try
			{
				return Integer.parseInt(value);
			}
			catch (NumberFormatException e)
			{
				// ignore
			}
		}

		return value;
	}

	private Object loadFromSecret(final String context, final String key) throws ApiException
	{
		V1SecretList secrets = api.listNamespacedSecret(namespace, null, null, null, null,
			LABEL_SELECTOR, null, null, null, null);
		for (V1Secret secret : secrets.getItems())
		{
			String ann = null;
			if (secret.getMetadata().getAnnotations() != null)
			{
				ann = secret.getMetadata().getAnnotations().get(ANNOTATION_KEY);
			}

			if ((context == null && ann != null) || (context != null && ann == null)
				|| (context != null && ann != null && !context.equalsIgnoreCase(ann)))
			{
				logger.debugv(
					"Skipping secret ''{3}'' (annotation {2}) for key ''{1}'' (context ''{0}'')",
					context, key, ann, secret.getMetadata().getName());
				continue;
			}

			byte[] value = secret.getData().get(key);
			if (value != null)
			{
				logger.debugv("Found key ''{1}'' (context ''{2}'') in secret ''{3}''",
					new String(value), key, context != null ? context : "/",
					secret.getMetadata().getName());
				return new String(value);
			}
		}
		logger.debugv("Key ''{0}'' (context {1}) not found in secret", key, context);
		return null;
	}

	private String getContext(final Name name)
	{
		String fn = name.toString();
		int i = fn.lastIndexOf('/');
		return i > 0 ? fn.substring(0, i) : null;
	}

	private String getKeyWithoutContext(final Name name)
	{
		String fn = name.toString();
		int i = fn.lastIndexOf('/');
		return i > 0 ? fn.substring(i + 1) : fn;
	}

	public Object get(final Name key) throws NamingException
	{
		try
		{
			Optional<Object> result = values.get(key);
			if (result.isPresent())
			{
				return result.get();
			}
			throw new NamingException("Key " + key.toString() + " not found!");
		}
		catch (ExecutionException | UncheckedExecutionException e)
		{
			NamingException ne =
				new NamingException("Failed to load value for key '" + key.toString() + "'");
			ne.setRootCause(e);
			throw ne;
		}
	}
}