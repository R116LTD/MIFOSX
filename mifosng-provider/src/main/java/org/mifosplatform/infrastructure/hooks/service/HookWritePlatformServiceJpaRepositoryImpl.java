/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.mifosplatform.infrastructure.hooks.service;

import static org.mifosplatform.infrastructure.hooks.api.HookApiConstants.actionNameParamName;
import static org.mifosplatform.infrastructure.hooks.api.HookApiConstants.configParamName;
import static org.mifosplatform.infrastructure.hooks.api.HookApiConstants.entityNameParamName;
import static org.mifosplatform.infrastructure.hooks.api.HookApiConstants.eventsParamName;
import static org.mifosplatform.infrastructure.hooks.api.HookApiConstants.nameParamName;
import static org.mifosplatform.infrastructure.hooks.api.HookApiConstants.webTemplateName;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.mifosplatform.infrastructure.core.api.JsonCommand;
import org.mifosplatform.infrastructure.core.data.ApiParameterError;
import org.mifosplatform.infrastructure.core.data.CommandProcessingResult;
import org.mifosplatform.infrastructure.core.data.CommandProcessingResultBuilder;
import org.mifosplatform.infrastructure.core.data.DataValidatorBuilder;
import org.mifosplatform.infrastructure.core.exception.PlatformApiDataValidationException;
import org.mifosplatform.infrastructure.core.exception.PlatformDataIntegrityException;
import org.mifosplatform.infrastructure.core.serialization.FromJsonHelper;
import org.mifosplatform.infrastructure.hooks.domain.Hook;
import org.mifosplatform.infrastructure.hooks.domain.HookConfiguration;
import org.mifosplatform.infrastructure.hooks.domain.HookRepository;
import org.mifosplatform.infrastructure.hooks.domain.HookResource;
import org.mifosplatform.infrastructure.hooks.domain.HookTemplate;
import org.mifosplatform.infrastructure.hooks.domain.HookTemplateRepository;
import org.mifosplatform.infrastructure.hooks.domain.Schema;
import org.mifosplatform.infrastructure.hooks.exception.HookNotFoundException;
import org.mifosplatform.infrastructure.hooks.exception.HookTemplateNotFoundException;
import org.mifosplatform.infrastructure.hooks.serialization.HookCommandFromApiJsonDeserializer;
import org.mifosplatform.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

@Service
public class HookWritePlatformServiceJpaRepositoryImpl implements
		HookWritePlatformService {

	private final PlatformSecurityContext context;
	private final HookRepository hookRepository;
	private final HookTemplateRepository hookTemplateRepository;
	private final HookCommandFromApiJsonDeserializer fromApiJsonDeserializer;
	private final FromJsonHelper fromApiJsonHelper;

	@Autowired
	public HookWritePlatformServiceJpaRepositoryImpl(
			final PlatformSecurityContext context,
			final HookRepository hookRepository,
			final HookTemplateRepository hookTemplateRepository,
			final HookCommandFromApiJsonDeserializer fromApiJsonDeserializer,
			final FromJsonHelper fromApiJsonHelper) {
		this.context = context;
		this.hookRepository = hookRepository;
		this.hookTemplateRepository = hookTemplateRepository;
		this.fromApiJsonDeserializer = fromApiJsonDeserializer;
		this.fromApiJsonHelper = fromApiJsonHelper;
	}

	@Transactional
	@Override
	public CommandProcessingResult createHook(final JsonCommand command) {

		try {
			this.context.authenticatedUser();

			this.fromApiJsonDeserializer.validateForCreate(command.json());

			final HookTemplate template = retrieveHookTemplateBy(command
					.stringValueOfParameterNamed(nameParamName));
			final String configJson = command.jsonFragment(configParamName);
			final Set<HookConfiguration> config = assembleConfig(
					command.mapValueOfParameterNamed(configJson), template);
			final JsonArray events = command
					.arrayOfParameterNamed(eventsParamName);
			final Set<HookResource> allEvents = assembleSetOfEvents(events);
			final Hook hook = Hook.fromJson(command, template, config,
					allEvents);

			validateHookRules(template, config);

			this.hookRepository.save(hook);

			return new CommandProcessingResultBuilder()
					.withCommandId(command.commandId())
					.withEntityId(hook.getId()).build();
		} catch (final DataIntegrityViolationException dve) {
			handleHookDataIntegrityIssues(command, dve);
			return CommandProcessingResult.empty();
		}
	}

	@Transactional
	@Override
	public CommandProcessingResult updateHook(final Long hookId,
			final JsonCommand command) {

		try {
			this.context.authenticatedUser();

			this.fromApiJsonDeserializer.validateForUpdate(command.json());

			final Hook hook = retrieveHookBy(hookId);
			final HookTemplate template = hook.getHookTemplate();
			final Map<String, Object> changes = hook.update(command);

			if (!changes.isEmpty()) {

				if (changes.containsKey(eventsParamName)) {
					final Set<HookResource> events = assembleSetOfEvents(command
							.arrayOfParameterNamed(eventsParamName));
					final boolean updated = hook.updateEvents(events);
					if (!updated) {
						changes.remove(eventsParamName);
					}
				}

				if (changes.containsKey(configParamName)) {
					final String configJson = command
							.jsonFragment(configParamName);
					final Set<HookConfiguration> config = assembleConfig(
							command.mapValueOfParameterNamed(configJson),
							template);
					final boolean updated = hook.updateConfig(config);
					if (!updated) {
						changes.remove(configParamName);
					}
				}

				this.hookRepository.saveAndFlush(hook);
			}

			return new CommandProcessingResultBuilder() //
					.withCommandId(command.commandId()) //
					.withEntityId(hookId) //
					.with(changes) //
					.build();
		} catch (final DataIntegrityViolationException dve) {
			handleHookDataIntegrityIssues(command, dve);
			return null;
		}
	}

	@Transactional
	@Override
	public CommandProcessingResult deleteHook(final Long hookId) {

		this.context.authenticatedUser();

		final Hook hook = retrieveHookBy(hookId);

		try {
			this.hookRepository.delete(hook);
			this.hookRepository.flush();
		} catch (final DataIntegrityViolationException e) {
			throw new PlatformDataIntegrityException(
					"error.msg.unknown.data.integrity.issue",
					"Unknown data integrity issue with resource: "
							+ e.getMostSpecificCause());
		}
		return new CommandProcessingResultBuilder().withEntityId(hookId)
				.build();
	}

	private Hook retrieveHookBy(final Long hookId) {
		final Hook hook = this.hookRepository.findOne(hookId);
		if (hook == null) {
			throw new HookNotFoundException(hookId.toString());
		}
		return hook;
	}

	private HookTemplate retrieveHookTemplateBy(final String templateName) {
		final HookTemplate template = this.hookTemplateRepository
				.findOne(templateName);
		if (template == null) {
			throw new HookTemplateNotFoundException(templateName);
		}
		return template;
	}

	private Set<HookConfiguration> assembleConfig(
			final Map<String, String> hookConfig, final HookTemplate template) {

		final Set<HookConfiguration> configuration = new HashSet<>();
		final Set<Schema> fields = template.getSchema();

		for (final Entry<String, String> configEntry : hookConfig.entrySet()) {
			for (final Schema field : fields) {
				if (field.getFieldName().equalsIgnoreCase(configEntry.getKey())) {
					final HookConfiguration config = HookConfiguration
							.createNewWithoutHook(field.getFieldType(),
									configEntry.getKey(),
									configEntry.getValue());
					configuration.add(config);
					break;
				}
			}

		}

		return configuration;
	}

	private Set<HookResource> assembleSetOfEvents(final JsonArray eventsArray) {

		final Set<HookResource> allEvents = new HashSet<>();

		for (int i = 0; i < eventsArray.size(); i++) {

			final JsonObject eventElement = eventsArray.get(i)
					.getAsJsonObject();

			final String entityName = this.fromApiJsonHelper
					.extractStringNamed(entityNameParamName, eventElement);
			final String actionName = this.fromApiJsonHelper
					.extractStringNamed(actionNameParamName, eventElement);
			final HookResource event = HookResource.createNewWithoutHook(
					entityName, actionName);
			allEvents.add(event);
		}

		return allEvents;
	}

	private void validateHookRules(final HookTemplate template,
			final Set<HookConfiguration> config) {

		final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
		final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(
				dataValidationErrors).resource("hook");

		if (!template.getName().equalsIgnoreCase(webTemplateName)
				&& this.hookRepository.findOneByTemplateId(template.getId()) != null) {
			final String errorMessage = "multiple.non.web.template.hooks.not.supported";
			baseDataValidator.reset().failWithCodeNoParameterAddedToErrorCode(
					errorMessage);
		}

		final Set<Schema> fields = template.getSchema();
		for (final Schema field : fields) {
			if (!field.isOptional()) {
				boolean found = false;
				for (final HookConfiguration conf : config) {
					if (field.getFieldName().equals(conf.getFieldName())) {
						found = true;
					}
				}
				if (!found) {
					final String errorMessage = "required.config.field."
							+ field.getFieldName() + ".not.provided";
					baseDataValidator.reset()
							.failWithCodeNoParameterAddedToErrorCode(
									errorMessage);
				}
			}
		}

		if (!dataValidationErrors.isEmpty()) {
			throw new PlatformApiDataValidationException(dataValidationErrors);
		}
	}

	private void handleHookDataIntegrityIssues(final JsonCommand command,
			final DataIntegrityViolationException dve) {
		final Throwable realCause = dve.getMostSpecificCause();
		if (realCause.getMessage().contains("hook_name")) {
			final String name = command.stringValueOfParameterNamed("name");
			throw new PlatformDataIntegrityException(
					"error.msg.hook.duplicate.name", "A hook with name '"
							+ name + "' already exists", "name", name);
		}

		throw new PlatformDataIntegrityException(
				"error.msg.unknown.data.integrity.issue",
				"Unknown data integrity issue with resource: "
						+ realCause.getMessage());
	}
}
