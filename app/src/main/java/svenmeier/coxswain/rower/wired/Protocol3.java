/*
 * Copyright 2015 Sven Meier
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
package svenmeier.coxswain.rower.wired;

import svenmeier.coxswain.gym.Measurement;
import svenmeier.coxswain.rower.wired.usb.Consumer;
import svenmeier.coxswain.rower.wired.usb.ITransfer;

public class Protocol3 implements IProtocol {

    private static final long DEFAULT_THROTTLE = 50;

    private static final int TIMEOUT = 100;

    private final ITrace trace;

    private final ITransfer transfer;

    private long throttle = DEFAULT_THROTTLE;

    private long lastTransfer = 0;

    private long start;

    private int distanceInDecimeters;

    public final RatioCalculator ratioCalculator = new RatioCalculator();

    public Protocol3(ITransfer transfer, ITrace trace) {
        this.transfer = transfer;

        transfer.setTimeout(TIMEOUT);
        transfer.setBaudrate(1200);
        transfer.setData(8, ITransfer.PARITY_NONE, ITransfer.STOP_BIT_1_0, false);

        this.trace = trace;
        trace.comment("protocol 3");
    }

    public void setThrottle(long throttle) {
        this.throttle = throttle;
    }

    @Override
    public void reset() {
        distanceInDecimeters = 0;

        this.start = System.currentTimeMillis();

        ratioCalculator.clear(start);
    }

    @Override
    public void transfer(Measurement measurement) {

        if (System.currentTimeMillis() - lastTransfer < throttle) {
            return;
        }
        lastTransfer = System.currentTimeMillis();

        Consumer consumer = transfer.consumer();
        while (consumer.hasNext()) {
            switch (consumer.next()) {
                case (byte)0xFB:
                    if (!consumer.hasNext()) {
                        break;
                    }
                    measurement.pulse = consumer.next() & 0xFF;

                    trace(consumer.consumed());
                    continue;
                case (byte)0xFC:
                    //trace(buffer, c, 1);
                    measurement.strokes = measurement.strokes + 1;

                    ratioCalculator.recovering(measurement, System.currentTimeMillis());

                    trace(consumer.consumed());
                    continue;
                case (byte)0xFD:
                    // 2 bytes voltage not used
                    if (!consumer.hasNext()) {
                        break;
                    }
                    consumer.next();
                    if (!consumer.hasNext()) {
                        break;
                    }
                    consumer.next();

                    ratioCalculator.pulling(measurement, System.currentTimeMillis());

                    trace(consumer.consumed());
                    continue;
                case (byte)0xFE:
                    if (!consumer.hasNext()) {
                        break;
                    }

                    distanceInDecimeters += consumer.next() & 0xFF;
                    measurement.distance = distanceInDecimeters / 10;

                    trace(consumer.consumed());
                    continue;
                case (byte)0xFF:
                    if (!consumer.hasNext()) {
                        break;
                    }
                    byte strokeRate = consumer.next();
                    if (!consumer.hasNext()) {
                        break;
                    }
                    byte speed = consumer.next();

                    measurement.strokeRate = strokeRate & 0xFF;
                    measurement.speed = (speed & 0xFF) * 10;

                    trace(consumer.consumed());
                    continue;
                default:
                    trace(consumer.consumed());
                    trace.comment("unrecognized");
            }
        }

        measurement.duration = (int)(System.currentTimeMillis() - start) / 1000;
    }

    private void trace(byte[] buffer) {
        StringBuilder string = new StringBuilder(buffer.length * 3);

        for (int c = 0; c < buffer.length; c++) {
            if (c > 0) {
                string.append(' ');
            }

            int b = buffer[c] & 0xFF;

            string.append(hex[b >>> 4]);
            string.append(hex[b & 0x0F]);
        }

        trace.onInput(string);
    }

    private static final char[] hex = "0123456789ABCDEF".toCharArray();
}
