/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Floodgate
 */

package org.geysermc.floodgate.pluginmessage.channel;

import com.google.common.base.Charsets;
import com.google.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.impl.FormDefinition;
import org.geysermc.cumulus.form.impl.FormDefinitions;
import org.geysermc.floodgate.api.logger.FloodgateLogger;
import org.geysermc.floodgate.api.player.FloodgatePlayer;
import org.geysermc.floodgate.api.player.PropertyKey;
import org.geysermc.floodgate.config.FloodgateConfig;
import org.geysermc.floodgate.platform.pluginmessage.PluginMessageUtils;
import org.geysermc.floodgate.player.FloodgatePlayerImpl;
import org.geysermc.floodgate.pluginmessage.PluginMessageChannel;

public class FormChannel implements PluginMessageChannel {
    private static final PropertyKey PROPERTY_LAST_FORM_ID = new PropertyKey("floodgate:last_form_id", true, false);
    private static final PropertyKey PROPERTY_ACTIVE_FORMS = new PropertyKey("floodgate:active_forms", true, true);

    private final FormDefinitions formDefinitions = FormDefinitions.instance();
    private final Map<UUID, AtomicInteger> fallbackNextFormIds = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Short, Form>> fallbackActiveForms = new ConcurrentHashMap<>();

    @Inject private PluginMessageUtils pluginMessageUtils;
    @Inject private FloodgateConfig config;
    @Inject private FloodgateLogger logger;

    @Override
    public String getIdentifier() {
        return "floodgate:form";
    }

    @Override
    public Result handleProxyCall(
            byte[] data,
            FloodgatePlayer source,
            Identity sourceIdentity
    ) {
        if (sourceIdentity == Identity.SERVER) {
            // send it to the client
            return Result.forward();
        }

        if (sourceIdentity == Identity.PLAYER) {
            if (data.length < 2) {
                return Result.kick("Invalid form response");
            }

            short formId = getFormId(data);

            // if the bit is not set, it's for the connected server
            if ((formId & 0x8000) == 0) {
                return Result.forward();
            }

            if (!callResponseConsumer(source, data)) {
                logger.error("Couldn't find stored form with id {} for player {}",
                        formId, source.getCorrectUsername());
            }
        }
        return Result.handled();
    }

    @Override
    public Result handleServerCall(byte[] data, FloodgatePlayer source) {
        if (!callResponseConsumer(source, data)) {
            logger.error("Couldn't find stored form for player {}", source.getCorrectUsername());
        }
        return Result.handled();
    }

    @Override
    public boolean supportsServerCallWithoutPlayer() {
        return true;
    }

    @Override
    public Result handleServerCallWithoutPlayer(
            byte[] data,
            UUID sourceUuid,
            String sourceUsername
    ) {
        if (data.length < 2) {
            return Result.kick("Invalid form response");
        }
        if (!callResponseConsumer(sourceUuid, data)) {
            logger.error("Couldn't find stored form for player {}", sourceUsername);
        }
        return Result.handled();
    }

    public boolean closeForm(FloodgatePlayer player) {
        closeForms0(player);
        return pluginMessageUtils.sendMessage(player.getCorrectUniqueId(), getIdentifier(), new byte[0]);
    }

    private void closeForms0(FloodgatePlayer player) {
        closeForms(player.removeProperty(PROPERTY_ACTIVE_FORMS));
    }

    private void closeForms(Map<Short, Form> forms) {
        if (forms != null && !forms.isEmpty()) {
            for (Form form : forms.values()) {
                try {
                    formDefinitions.definitionFor(form).handleFormResponse(form, "");
                } catch (Exception e) {
                    logger.error("Error while closing form!", e);
                }
            }
        }
    }

    public boolean sendForm(FloodgatePlayer player, Form form) {
        byte[] formData = createFormData(player, form);
        return pluginMessageUtils.sendMessage(player.getCorrectUniqueId(), getIdentifier(), formData);
    }

    public boolean sendForm(UUID playerUuid, Form form) {
        byte[] formData = createFormData(playerUuid, form);
        return pluginMessageUtils.sendMessage(playerUuid, getIdentifier(), formData);
    }

    public byte[] createFormData(FloodgatePlayer player, Form form) {
        short formId = getNextFormId(player);
        if (config.isProxy()) {
            formId |= (short) 0x8000;
        }

        ((FloodgatePlayerImpl) player)
                .getOrAddProperty(PROPERTY_ACTIVE_FORMS, ConcurrentHashMap::new)
                .put(formId, form);

        return createFormData(form, formId);
    }

    private byte[] createFormData(UUID playerUuid, Form form) {
        short formId = getNextFormId(playerUuid);
        if (config.isProxy()) {
            formId |= (short) 0x8000;
        }
        fallbackActiveForms
                .computeIfAbsent(playerUuid, unused -> new ConcurrentHashMap<>())
                .put(formId, form);

        return createFormData(form, formId);
    }

    private byte[] createFormData(Form form, short formId) {

        FormDefinition<Form, ?, ?> definition = formDefinitions.definitionFor(form);

        byte[] jsonData =
                definition.codec()
                        .jsonData(form)
                        .getBytes(Charsets.UTF_8);

        byte[] data = new byte[jsonData.length + 3];
        data[0] = (byte) definition.formType().ordinal();
        data[1] = (byte) (formId >> 8 & 0xFF);
        data[2] = (byte) (formId & 0xFF);
        System.arraycopy(jsonData, 0, data, 3, jsonData.length);
        return data;
    }

    protected boolean callResponseConsumer(FloodgatePlayer player, byte[] data) {
        short formId = getFormId(data);

        Map<Short, Form> forms = player.getProperty(PROPERTY_ACTIVE_FORMS);
        if (forms == null) {
            return false;
        }

        Form storedForm = forms.remove(formId);
        if (storedForm != null) {
            String responseData = new String(data, 2, data.length - 2, Charsets.UTF_8);
            try {
                formDefinitions.definitionFor(storedForm)
                        .handleFormResponse(storedForm, responseData);
            } catch (Exception e) {
                logger.error("Error while processing form response!", e);
            }
            return true;
        }
        return false;
    }

    private boolean callResponseConsumer(UUID playerUuid, byte[] data) {
        Map<Short, Form> forms = fallbackActiveForms.get(playerUuid);
        if (forms == null) {
            return false;
        }

        Form storedForm = forms.remove(getFormId(data));
        if (forms.isEmpty()) {
            fallbackActiveForms.remove(playerUuid, forms);
        }
        if (storedForm == null) {
            return false;
        }

        String responseData = new String(data, 2, data.length - 2, Charsets.UTF_8);
        try {
            formDefinitions.definitionFor(storedForm)
                    .handleFormResponse(storedForm, responseData);
        } catch (Exception e) {
            logger.error("Error while processing form response!", e);
        }
        return true;
    }

    public void disconnect(FloodgatePlayer player) {
        closeForms0(player);
    }

    public boolean closeForm(UUID playerUuid) {
        disconnect(playerUuid);
        return pluginMessageUtils.sendMessage(playerUuid, getIdentifier(), new byte[0]);
    }

    public void disconnect(UUID playerUuid) {
        fallbackNextFormIds.remove(playerUuid);
        closeForms(fallbackActiveForms.remove(playerUuid));
    }

    private short getFormId(byte[] data) {
        return (short) ((data[0] & 0xFF) << 8 | data[1] & 0xFF);
    }

    private short getNextFormId(FloodgatePlayer player) {
        AtomicInteger nextFormId =
                ((FloodgatePlayerImpl) player).getOrAddProperty(PROPERTY_LAST_FORM_ID, AtomicInteger::new);

        // signed bit is used to check if the form is from a proxy or a server
        return (short) nextFormId.getAndUpdate(
                (number) -> number == Short.MAX_VALUE ? 0 : number + 1);
    }

    private short getNextFormId(UUID playerUuid) {
        AtomicInteger nextFormId =
                fallbackNextFormIds.computeIfAbsent(playerUuid, unused -> new AtomicInteger());
        return (short) nextFormId.getAndUpdate(
                number -> number == Short.MAX_VALUE ? 0 : number + 1);
    }

}
