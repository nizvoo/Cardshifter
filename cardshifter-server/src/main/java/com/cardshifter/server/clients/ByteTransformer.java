package com.cardshifter.server.clients;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.cardshifter.api.messages.Message;
import com.cardshifter.api.messages.MessageTypeIdResolver;
import com.cardshifter.server.clients.serial.FieldsCollection;

public class ByteTransformer implements CommunicationTransformer {
	private static final Logger logger = LogManager.getLogger(ByteTransformer.class);

	public byte[] transform(Message message) throws IOException {
		FieldsCollection<Message> fields = FieldsCollection.gather(message);
		fields = fields.orderByName().putFirst("command");
		return fields.serialize(message);
	}
	
	@Override
	public void send(Message message, OutputStream out) throws IOException {
		// 1. find Fields to serialize
		// 2. order Fields, pre-process
		// 3. serialize
		logger.info("byte send " + message);
		FieldsCollection<Message> fields = FieldsCollection.gather(message);
		fields = fields.orderByName().putFirst("command");
		byte[] b = fields.serialize(message);
		out.write(b);
	}

	@Override
	public void read(InputStream in, Predicate<Message> onReceived) throws IOException {
		try {
			logger.info("Started reading " + this);
			Message message = readOnce(in);
			logger.info("byte recieve " + message);
			if (!onReceived.test(message)) {
				return;
			}
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException(e);
		}
	}

	public Message readOnce(InputStream in) throws IOException, NoSuchMethodException, SecurityException, InstantiationException, 
	  IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		DataInputStream data = new DataInputStream(in);
		int numBytes = data.readInt();
		logger.info("bytes received " + numBytes);
		byte[] actualData = new byte[numBytes];
		data.read(actualData);
		data = new DataInputStream(new ByteArrayInputStream(actualData));
		
		int typeLength = data.readInt();
		String str = readString(data, typeLength);
		System.out.println(str);
		Class<?> type = MessageTypeIdResolver.typeFor(str);
		Constructor<?> constructor = type.getDeclaredConstructor();
		constructor.setAccessible(true);
		Message message = (Message) constructor.newInstance();
		FieldsCollection<Message> fields = FieldsCollection.gather(message);
		fields = fields.orderByName().putFirst("command").skipFirst();
		fields.read(message, data);
		return message;
	}

	private String readString(DataInputStream data, int numBytes) throws IOException {
		byte[] bytes = new byte[numBytes * 2];
		data.read(bytes);
		return new String(bytes, StandardCharsets.UTF_16);
	}

}