/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.mifosplatform.infrastructure.hooks.listener;

import java.util.List;

import org.mifosplatform.infrastructure.core.domain.MifosPlatformTenant;
import org.mifosplatform.infrastructure.core.service.ThreadLocalContextUtil;
import org.mifosplatform.infrastructure.hooks.domain.Hook;
import org.mifosplatform.infrastructure.hooks.domain.HookRepository;
import org.mifosplatform.infrastructure.hooks.event.HookEvent;
import org.mifosplatform.infrastructure.hooks.event.HookEventSource;
import org.mifosplatform.infrastructure.hooks.processor.HookProcessor;
import org.mifosplatform.infrastructure.hooks.processor.HookProcessorProvider;
import org.mifosplatform.infrastructure.security.service.TenantDetailsService;
import org.mifosplatform.useradministration.domain.AppUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MifosHookListener implements HookListener {

	private final HookProcessorProvider hookProcessorProvider;
	private final HookRepository hookRepository;
	private final TenantDetailsService tenantDetailsService;

	@Autowired
	public MifosHookListener(final HookProcessorProvider hookProcessorProvider,
			final HookRepository hookRepository,
			final TenantDetailsService tenantDetailsService) {
		this.hookRepository = hookRepository;
		this.hookProcessorProvider = hookProcessorProvider;
		this.tenantDetailsService = tenantDetailsService;
	}

	@Override
	public void onApplicationEvent(final HookEvent event) {

		final String tenantIdentifier = event.getTenantIdentifier();
		final MifosPlatformTenant tenant = this.tenantDetailsService
				.loadTenantById(tenantIdentifier);
		ThreadLocalContextUtil.setTenant(tenant);

		final AppUser appUser = event.getAppUser();
		final String authToken = event.getAuthToken();

		final HookEventSource hookEventSource = event.getSource();
		final String entityName = hookEventSource.getEntityName();
		final String actionName = hookEventSource.getActionName();
		final String payload = event.getPayload();

		final List<Hook> hooks = this.hookRepository
				.findAllHooksListeningToEvent(hookEventSource.getEntityName(),
						hookEventSource.getActionName());
		for (final Hook hook : hooks) {
			final HookProcessor processor = this.hookProcessorProvider
					.getProcessor(hook);
			processor.process(hook, appUser, payload, entityName, actionName,
					tenantIdentifier, authToken);
		}
	}

}
