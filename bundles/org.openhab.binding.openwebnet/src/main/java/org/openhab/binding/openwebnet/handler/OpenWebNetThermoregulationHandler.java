/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.openwebnet.handler;

import static org.openhab.binding.openwebnet.OpenWebNetBindingConstants.*;

import java.math.BigDecimal;
import java.util.Set;

import javax.measure.Unit;
import javax.measure.quantity.Temperature;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.openwebnet.OpenWebNetBindingConstants;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.UnDefType;
import org.openwebnet4j.communication.OWNException;
import org.openwebnet4j.communication.Response;
import org.openwebnet4j.message.BaseOpenMessage;
import org.openwebnet4j.message.FrameException;
import org.openwebnet4j.message.MalformedFrameException;
import org.openwebnet4j.message.Thermoregulation;
import org.openwebnet4j.message.Where;
import org.openwebnet4j.message.WhereThermo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link OpenWebNetThermoregulationHandler} is responsible for handling commands/messages for a Thermoregulation
 * OpenWebNet device. It extends the abstract {@link OpenWebNetThingHandler}.
 *
 * @author Massimo Valla - Initial contribution
 * @author Gilberto Cocchi - Contributor
 */
@NonNullByDefault
public class OpenWebNetThermoregulationHandler extends OpenWebNetThingHandler {

    private final Logger logger = LoggerFactory.getLogger(OpenWebNetThermoregulationHandler.class);

    private enum Mode {
        // TODO make it a single map and integrate it with Thermoregulation.WHAT to have automatic translation
        UNKNOWN("UNKNOWN"),
        MANUAL("MANUAL"),
        PROTECTION("PROTECTION"),
        OFF("OFF");

        private final String mode;

        Mode(final String mode) {
            this.mode = mode;
        }

        @Override
        public String toString() {
            return mode;
        }
    }

    public enum ThermoFunction {
        UNKNOWN(-1),
        COOL(0),
        HEAT(1);

        private final Integer value;

        private ThermoFunction(Integer value) {
            this.value = value;
        }

        public Integer value() {
            return value;
        }
    }

    public final static Set<ThingTypeUID> SUPPORTED_THING_TYPES = OpenWebNetBindingConstants.THERMOREGULATION_SUPPORTED_THING_TYPES;

    private Mode currentOperationMode = Mode.UNKNOWN;
    private Double currentSetPoint = Double.NaN;
    private ThermoFunction currentThermoFunction = ThermoFunction.UNKNOWN;
    private @Nullable Command callbackCommand;
    private @Nullable String callbackChannelType;

    public OpenWebNetThermoregulationHandler(Thing thing) {
        super(thing);
        logger.debug("==OWN:ThermoHandler== constructor");
    }

    @Override
    public void initialize() {
        super.initialize();
        logger.debug("==OWN:ThermoHandler== initialize() thing={}", thing.getUID());
        requestStatus();
    }

    @Override
    protected void requestChannelState(ChannelUID channel) {
        logger.debug("==OWN:ThermoHandler== requestChannelState() thingUID={} channel={}, deviceWhere={}",
                thing.getUID(), channel.getId(), deviceWhere);
        requestStatus();
    }

    @Override
    protected void handleChannelCommand(ChannelUID channelUID, Command command) {
        handleChannelCommand(command, channelUID.getId());
    }

    protected void handleChannelCommand(Command command, String channelType) {
        switch (channelType) {
            case CHANNEL_TEMP_SETPOINT:
                handleSetpointCommand(command);
                break;
            case CHANNEL_OPERATION_MODE:
                handleModeCommand(command);
                break;
            case CHANNEL_THERMO_FUNCTION:
                handleThermoFunctionCommand(command);
            default: {
                logger.warn("==OWN:ThermoHandler== Unsupported ChannelUID {}", channelType);
            }
        }
        // TODO if communication with thing fails for some reason,
        // indicate that by setting the status with detail information
        // updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
        // "Could not control device at IP address x.x.x.x");
    }

    private void handleSetpointCommand(Command command) {
        logger.debug("==OWN:ThermoHandler== handleSetpointCommand() (command={})", command);
        Unit<Temperature> unit = SIUnits.CELSIUS;

        if (currentThermoFunction == ThermoFunction.UNKNOWN) {
            logger.warn("==OWN:ThermoHandler== Cannot handle handleSetpointCommand(), thermoFunction is null");
            callbackCommand = command;
            callbackChannelType = CHANNEL_TEMP_SETPOINT;
            requestStatus();
        } else if (command instanceof QuantityType || command instanceof DecimalType) {
            BigDecimal value;
            if (command instanceof QuantityType) {
                QuantityType<Temperature> quantity = commandToQuantityType(command, unit);
                value = quantity.toBigDecimal();
            } else {
                value = ((DecimalType) command).toBigDecimal();
            }

            currentSetPoint = value.doubleValue();

            try {
                if (deviceWhere != null && currentSetPoint != Double.NaN) {
                    String targetWhere = deviceWhere.value();
                    Thermoregulation.MODE operationMode = getOperationMode(currentThermoFunction);
                    Response res = send(Thermoregulation.requestWriteSetpointTemperature(targetWhere, currentSetPoint,
                            operationMode));
                    if (res != null && res.getFinalResponse().isNACK()) {
                        logger.debug("=OWN:ThermoHandler== Failed sending Setpoint command with WHERE=N");
                    }
                }
            } catch (MalformedFrameException | OWNException e) {
                logger.warn("==OWN:ThermoHandler== handleSetpointCommand() got Exception on frame {}: {}", command,
                        e.getMessage());
            }
        } else {
            logger.warn("==OWN:ThermoHandler== Cannot handle command {} for thing {}", command, getThing().getUID());
            return;
        }
    }

    private void handleThermoFunctionCommand(Command command) {
        logger.debug("==OWN:ThermoHandler== handleThermoFunctionCommand() (command={})", command);
        if (currentSetPoint == Double.NaN) {
            logger.warn("==OWN:ThermoHandler== Cannot handle handleThermoFunctionCommand(), currentSetPoint is null");
            callbackCommand = command;
            callbackChannelType = CHANNEL_THERMO_FUNCTION;
            requestStatus();
        } else if (command instanceof StringType) {
            Thermoregulation.MODE newOperationMode = getOperationMode(
                    ThermoFunction.valueOf(((StringType) command).toString()));
            if (deviceWhere != null) {
                String targetWhere = deviceWhere.value();
                try {
                    Response res = send(Thermoregulation.requestWriteSetpointTemperature(targetWhere, currentSetPoint,
                            newOperationMode));
                    if (res != null && res.getFinalResponse().isNACK()) {
                        logger.debug("=OWN:ThermoHandler== Failed sending Setpoint command with WHERE=N");
                    }
                } catch (MalformedFrameException | OWNException e) {
                    logger.warn("==OWN:ThermoHandler== handleSetpointCommand() got Exception on frame {}: {}", command,
                            e.getMessage());
                }
            } else {
                logger.warn(
                        "==OWN:ThermoHandler== Cannot handle handleThermoFunctionCommand() with command {} for thing {}",
                        command, getThing().getUID());
            }
        } else {
            logger.warn("==OWN:ThermoHandler== handleThermoFunctionCommand() Cannot handle command {} for thing {}",
                    command, getThing().getUID());
        }
    }

    private void handleModeCommand(Command command) {
        logger.debug("==OWN:ThermoHandler== handleModeCommand() (command={})", command);

        if (command instanceof StringType) {
            Thermoregulation.WHAT modeWhat = null;
            try {

                Mode mode = Mode.valueOf(((StringType) command).toString());
                modeWhat = modeToWhat(mode);

                logger.debug("==OWN:ThermoHandler== handleModeCommand() modeWhat={}", modeWhat);

                if (modeWhat != null && deviceWhere != null) {

                    String targetWhere = deviceWhere.value();

                    logger.debug("==OWN:ThermoHandler== handleModeCommand() (currentSetPoint={})", currentSetPoint);
                    try {
                        if (((StringType) command).toString().equals("OFF")) {
                            send(Thermoregulation.requestTurnOff(targetWhere));
                        } else if (currentSetPoint == Double.NaN) {
                            // Request status to fetch setPoint Temperature and change Operation Mode later.
                            callbackCommand = command;
                            callbackChannelType = CHANNEL_OPERATION_MODE;
                            requestStatus();
                        } else {
                            // Use WriteSetPointTemperature method to change the ThermoFunction from Heating to Cooling
                            // or vice versa
                            Thermoregulation.MODE operationMode = getOperationMode(currentThermoFunction);
                            send(Thermoregulation.requestWriteSetpointTemperature(targetWhere, currentSetPoint,
                                    operationMode));
                        }
                    } catch (MalformedFrameException | OWNException e) {
                        logger.warn("==OWN:ThermoHandler== Cannot handle command {} for thing {}. Exception: {}",
                                command, getThing().getUID(), e.getMessage());
                    }
                } else {
                    logger.warn("==OWN:ThermoHandler== Cannot handle command {} for thing {}", command,
                            getThing().getUID());
                }
            } catch (IllegalArgumentException e) {
                logger.warn("==OWN:ThermoHandler== Cannot handle command {} for thing {}. Exception: {}", command,
                        getThing().getUID(), e.getMessage());
            }
        } else {
            logger.warn("==OWN:ThermoHandler== Cannot handle command {} for thing {}", command, getThing().getUID());
        }
    }

    @Override
    protected String ownIdPrefix() {
        return org.openwebnet4j.message.Who.THERMOREGULATION.value().toString();
    }

    @Override
    protected void handleMessage(BaseOpenMessage msg) {
        super.handleMessage(msg);

        if (msg.isCommand()) {
            updateMode((Thermoregulation) msg);
        } else {
            int messageDim = msg.getDim().value();

            if (messageDim == Thermoregulation.DIM.TEMPERATURE.value()
                    || messageDim == Thermoregulation.DIM.PROBE_TEMPERATURE.value()) {
                updateTemperature((Thermoregulation) msg);
            } else if (messageDim == Thermoregulation.DIM.TEMP_SETPOINT.value()
                    || messageDim == Thermoregulation.DIM.TEMP_TARGET.value()) {
                updateSetpoint((Thermoregulation) msg);
                if (callbackChannelType != null && callbackCommand != null) {
                    handleChannelCommand(callbackCommand, callbackChannelType);
                    callbackCommand = null;
                    callbackChannelType = null;
                }
            } else {
                logger.debug("==OWN:ThermoHandler== handleMessage() Ignoring unsupported DIM for thing {}. Frame={}",
                        getThing().getUID(), msg);
            }
        }
    }

    private void updateMode(Thermoregulation tmsg) {
        logger.debug("==OWN:ThermoHandler== updateMode() for thing: {} msg={}", thing.getUID(), tmsg);
        Thermoregulation.WHAT w = (Thermoregulation.WHAT) tmsg.getWhat();
        Mode newMode = whatToMode(w);
        if (newMode != null) {
            updateOperationMode(newMode);
        } else {
            logger.debug("==OWN:ThermoHandler== updateMode() mode not processed: msg={}", tmsg);
        }
        updateThermoFunction(w);
    }

    private void updateThermoFunction(Thermoregulation.WHAT what) {
        logger.debug("==OWN:ThermoHandler== updateThermoFunction() for thing: {}", thing.getUID());
        ThermoFunction newFunction = null;
        switch (what) {
            case CONDITIONING:
            case MANUAL_CONDITIONING:
            case PROTECTION_CONDITIONING:
            case OFF_CONDITIONING:
                newFunction = ThermoFunction.COOL;
                break;
            case HEATING:
            case MANUAL_HEATING:
            case PROTECTION_HEATING:
            case OFF_HEATING:
                newFunction = ThermoFunction.HEAT;
                break;
            default:
                break;
        }
        if (newFunction != null && currentThermoFunction != newFunction) {
            currentThermoFunction = newFunction;
            updateState(CHANNEL_THERMO_FUNCTION, new StringType(currentThermoFunction.toString()));
        } else {
            logger.debug("==OWN:ThermoHandler== updateThermoFunction() cannot handle thermofunction what={}", what);
        }
    }

    private void updateOperationMode(Mode mode) {
        logger.debug("==OWN:ThermoHandler== updateOperationMode() for thing: {}", thing.getUID());
        if (currentOperationMode != mode) {
            currentOperationMode = mode;
            updateState(CHANNEL_OPERATION_MODE, new StringType(currentOperationMode.toString()));
        }
    }

    private void updateTemperature(Thermoregulation tmsg) {
        logger.debug("==OWN:ThermoHandler== updateTemperature() for thing: {}", thing.getUID());
        Double temp;
        try {
            temp = Thermoregulation.parseTemperature(tmsg);
            updateState(CHANNEL_TEMPERATURE, new DecimalType(temp));
        } catch (NumberFormatException | FrameException e) {
            logger.warn("==OWN:ThermoHandler== updateTemperature() got Exception on frame {}: {}", tmsg,
                    e.getMessage());
            updateState(CHANNEL_TEMPERATURE, UnDefType.NULL);
        }
    }

    private void updateSetpoint(Thermoregulation tmsg) {
        logger.debug("==OWN:ThermoHandler== updateSetpoint() for thing: {}", thing.getUID());
        Double temp;
        try {
            temp = Thermoregulation.parseTemperature(tmsg);
            currentSetPoint = (new DecimalType(temp)).doubleValue();
            updateState(CHANNEL_TEMP_SETPOINT, new DecimalType(temp));
        } catch (NumberFormatException | FrameException e) {
            logger.warn("==OWN:ThermoHandler== updateSetpoint() got Exception on frame {}: {}", tmsg, e.getMessage());
            updateState(CHANNEL_TEMP_SETPOINT, UnDefType.NULL);
        }
    }

    private static @Nullable Mode whatToMode(Thermoregulation.WHAT w) {
        Mode m = null;
        switch (w) {
            case MANUAL_HEATING:
            case MANUAL_CONDITIONING:
            case MANUAL_GENERIC:
                m = Mode.MANUAL;
                break;
            case PROTECTION_HEATING:
            case PROTECTION_CONDITIONING:
            case PROTECTION_GENERIC:
            case OFF_HEATING:
            case OFF_CONDITIONING:
            case OFF_GENERIC:
                m = Mode.OFF;
                break;
            case CONDITIONING:
                m = Mode.MANUAL;
                break;
            case HEATING:
                m = Mode.MANUAL;
                break;
            default:
                break;
        }
        return m;
    }

    private Thermoregulation.@Nullable WHAT modeToWhat(Mode m) {
        Thermoregulation.WHAT newWhat = null;
        switch (m) {
            case MANUAL:
                if (currentThermoFunction == ThermoFunction.COOL) {
                    newWhat = Thermoregulation.WHAT.MANUAL_CONDITIONING;
                } else {
                    newWhat = Thermoregulation.WHAT.MANUAL_HEATING;
                }
                break;
            case PROTECTION:
                if (currentThermoFunction == ThermoFunction.COOL) {
                    newWhat = Thermoregulation.WHAT.PROTECTION_CONDITIONING;
                } else {
                    newWhat = Thermoregulation.WHAT.PROTECTION_HEATING;
                }
                break;
            case OFF:
            case UNKNOWN:
                if (currentThermoFunction == ThermoFunction.COOL) {
                    newWhat = Thermoregulation.WHAT.OFF_CONDITIONING;
                } else {
                    newWhat = Thermoregulation.WHAT.OFF_HEATING;
                }
                break;
        }
        return newWhat;
    }

    protected void requestStatus() {
        logger.debug("==OWN:ThermoHandler== requestStatus() for deviceWhere={}", deviceWhere);
        if (deviceWhere != null) {
            try {
                send(Thermoregulation.requestStatus(deviceWhere.value()));
            } catch (OWNException e) {
                logger.warn("requestStatus() Exception while requesting thermostat state: {}", e.getMessage());
            }
        }
    }

    private Thermoregulation.MODE getOperationMode(ThermoFunction thermoF) {
        Thermoregulation.MODE operationMode = null;
        switch (thermoF) {
            case HEAT:
                operationMode = Thermoregulation.MODE.HEATING;
                break;
            case COOL:
                operationMode = Thermoregulation.MODE.CONDITIONING;
                break;
            default:
                operationMode = Thermoregulation.MODE.GENERIC;
                break;
        }
        return operationMode;
    }

    @Override
    protected Where buildBusWhere(String wStr) throws IllegalArgumentException {
        return new WhereThermo(wStr);
    }

    @Override
    public void thingUpdated(Thing thing) {
        super.thingUpdated(thing);
        logger.debug("==OWN:ThermoHandler== thingUpdated()");
    }
}
