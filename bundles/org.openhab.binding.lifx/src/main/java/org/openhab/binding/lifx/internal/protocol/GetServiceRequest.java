/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
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
package org.openhab.binding.lifx.internal.protocol;

import java.nio.ByteBuffer;

/**
 * @author Tim Buckley - Initial Contribution
 * @author Karel Goderis - Enhancement for the V2 LIFX Firmware and LAN Protocol Specification
 */
public class GetServiceRequest extends Packet {

    public static final int TYPE = 0x02;

    public GetServiceRequest() {
        setTagged(true);
        setAddressable(true);
    }

    @Override
    public int packetLength() {
        return 0;
    }

    @Override
    public int packetType() {
        return TYPE;
    }

    @Override
    protected void parsePacket(ByteBuffer bytes) {
        // empty
    }

    @Override
    protected ByteBuffer packetBytes() {
        return ByteBuffer.allocate(0);
    }

    @Override
    public int[] expectedResponses() {
        return new int[] { StateServiceResponse.TYPE };
    }
}
