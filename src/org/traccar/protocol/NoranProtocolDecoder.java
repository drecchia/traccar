/*
 * Copyright 2013 - 2015 Anton Tananaev (anton.tananaev@gmail.com)
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
package org.traccar.protocol;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.helper.BitUtil;
import org.traccar.helper.DateBuilder;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Position;

import java.net.SocketAddress;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

public class NoranProtocolDecoder extends BaseProtocolDecoder {

    public NoranProtocolDecoder(NoranProtocol protocol) {
        super(protocol);
    }

    public static final int MSG_UPLOAD_POSITION = 0x0008;
    public static final int MSG_UPLOAD_POSITION_NEW = 0x0032;
    public static final int MSG_CONTROL = 0x0002;
    public static final int MSG_CONTROL_RESPONSE = 0x8009;
    public static final int MSG_ALARM = 0x0003;
    public static final int MSG_SHAKE_HAND = 0x0000;
    public static final int MSG_SHAKE_HAND_RESPONSE = 0x8000;
    public static final int MSG_IMAGE_SIZE = 0x0200;
    public static final int MSG_IMAGE_PACKET = 0x0201;

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        ChannelBuffer buf = (ChannelBuffer) msg;

        buf.readUnsignedShort(); // length
        int type = buf.readUnsignedShort();

        if (type == MSG_SHAKE_HAND && channel != null) {

            ChannelBuffer response = ChannelBuffers.dynamicBuffer(ByteOrder.LITTLE_ENDIAN, 13);
            response.writeBytes(
                    ChannelBuffers.copiedBuffer(ByteOrder.LITTLE_ENDIAN, "\r\n*KW", StandardCharsets.US_ASCII));
            response.writeByte(0);
            response.writeShort(response.capacity());
            response.writeShort(MSG_SHAKE_HAND_RESPONSE);
            response.writeByte(1); // status
            response.writeBytes(
                    ChannelBuffers.copiedBuffer(ByteOrder.LITTLE_ENDIAN, "\r\n", StandardCharsets.US_ASCII));

            channel.write(response, remoteAddress);

        } else if (type == MSG_UPLOAD_POSITION || type == MSG_UPLOAD_POSITION_NEW
                || type == MSG_CONTROL_RESPONSE || type == MSG_ALARM) {

            boolean newFormat = false;
            if (type == MSG_UPLOAD_POSITION && buf.readableBytes() == 48
                    || type == MSG_ALARM && buf.readableBytes() == 48
                    || type == MSG_CONTROL_RESPONSE && buf.readableBytes() == 57) {
                newFormat = true;
            }

            Position position = new Position();
            position.setProtocol(getProtocolName());

            if (type == MSG_CONTROL_RESPONSE) {
                buf.readUnsignedInt(); // GIS ip
                buf.readUnsignedInt(); // GIS port
            }

            position.setValid(BitUtil.check(buf.readUnsignedByte(), 0));

            position.set(Position.KEY_ALARM, buf.readUnsignedByte());

            if (newFormat) {
                position.setSpeed(UnitsConverter.knotsFromKph(buf.readUnsignedInt()));
                position.setCourse(buf.readFloat());
            } else {
                position.setSpeed(UnitsConverter.knotsFromKph(buf.readUnsignedByte()));
                position.setCourse(buf.readUnsignedShort());
            }
            position.setLongitude(buf.readFloat());
            position.setLatitude(buf.readFloat());

            if (!newFormat) {
                long timeValue = buf.readUnsignedInt();
                DateBuilder dateBuilder = new DateBuilder()
                        .setYear((int) BitUtil.from(timeValue, 26))
                        .setMonth((int) BitUtil.between(timeValue, 22, 26))
                        .setDay((int) BitUtil.between(timeValue, 17, 22))
                        .setHour((int) BitUtil.between(timeValue, 12, 17))
                        .setMinute((int) BitUtil.between(timeValue, 6, 12))
                        .setSecond((int) BitUtil.to(timeValue, 6));
                position.setTime(dateBuilder.getDate());
            }

            ChannelBuffer rawId;
            if (newFormat) {
                rawId = buf.readBytes(12);
            } else {
                rawId = buf.readBytes(11);
            }
            String id = rawId.toString(StandardCharsets.US_ASCII).replaceAll("[^\\p{Print}]", "");
            if (!identify(id, channel, remoteAddress)) {
                return null;
            }
            position.setDeviceId(getDeviceId());

            if (newFormat) {
                DateFormat dateFormat = new SimpleDateFormat("yy-MM-dd HH:mm:ss");
                position.setTime(dateFormat.parse(buf.readBytes(17).toString(StandardCharsets.US_ASCII)));
                buf.readByte();
            }

            if (!newFormat) {
                position.set(Position.PREFIX_IO + 1, buf.readUnsignedByte());
                position.set(Position.KEY_FUEL, buf.readUnsignedByte());
            } else if (type == MSG_UPLOAD_POSITION_NEW) {
                position.set(Position.PREFIX_TEMP + 1, buf.readShort());
                position.set(Position.KEY_ODOMETER, buf.readFloat());
            }

            return position;
        }

        return null;
    }

}
