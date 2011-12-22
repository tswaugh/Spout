package org.getspout.server.net.codec;

import java.io.IOException;

import org.getspout.server.msg.ExperienceMessage;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;

public class ExperienceCodec extends MessageCodec<ExperienceMessage> {
	public ExperienceCodec() {
		super(ExperienceMessage.class, 0x2B);
	}

	@Override
	public ChannelBuffer encode(ExperienceMessage message) throws IOException {
		ChannelBuffer buffer = ChannelBuffers.buffer(8);
		buffer.writeFloat(message.getBarValue());
		buffer.writeShort(message.getLevel());
		buffer.writeShort(message.getTotalExp());
		return buffer;
	}

	@Override
	public ExperienceMessage decode(ChannelBuffer buffer) throws IOException {
		float barValue = buffer.readFloat();
		short level = buffer.readShort();
		short totalExp = buffer.readShort();
		return new ExperienceMessage(barValue, level, totalExp);
	}
}
