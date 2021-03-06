package us.myles.ViaVersion.api;

import com.google.common.base.Preconditions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Getter;
import lombok.Setter;
import us.myles.ViaVersion.api.data.UserConnection;
import us.myles.ViaVersion.api.protocol.Protocol;
import us.myles.ViaVersion.api.remapper.ValueCreator;
import us.myles.ViaVersion.api.type.Type;
import us.myles.ViaVersion.api.type.TypeConverter;
import us.myles.ViaVersion.exception.InformativeException;
import us.myles.ViaVersion.handlers.ViaDecodeHandler;
import us.myles.ViaVersion.packets.Direction;
import us.myles.ViaVersion.packets.State;
import us.myles.ViaVersion.protocols.base.ProtocolInfo;
import us.myles.ViaVersion.util.PipelineUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class PacketWrapper {
    private final ByteBuf inputBuffer;
    private final UserConnection userConnection;
    private boolean send = true;
    @Setter
    @Getter
    private int id = -1;
    private LinkedList<Pair<Type, Object>> readableObjects = new LinkedList<>();
    private List<Pair<Type, Object>> packetValues = new ArrayList<>();

    public PacketWrapper(int packetID, ByteBuf inputBuffer, UserConnection userConnection) {
        this.id = packetID;
        this.inputBuffer = inputBuffer;
        this.userConnection = userConnection;
    }

    /**
     * Get a part from the output
     *
     * @param type  The type of the part you wish to get.
     * @param <T>   The return type of the type you wish to get.
     * @param index The index of the part (relative to the type)
     * @return The requested type or throws ArrayIndexOutOfBounds
     * @throws Exception If it fails to find it, an exception will be thrown.
     */
    public <T> T get(Type<T> type, int index) throws Exception {
        int currentIndex = 0;
        for (Pair<Type, Object> packetValue : packetValues) {
            if (packetValue.getKey() == type) { // Ref check
                if (currentIndex == index) {
                    return (T) packetValue.getValue();
                }
                currentIndex++;
            }
        }

        Exception e = new ArrayIndexOutOfBoundsException("Could not find type " + type.getTypeName() + " at " + index);
        throw new InformativeException(e).set("Type", type.getTypeName()).set("Index", index).set("Packet ID", getId()).set("Data", packetValues);
    }

    /**
     * Check if a type is at an index
     *
     * @param type  The type of the part you wish to get.
     * @param index The index of the part (relative to the type)
     * @return True if the type is at the index
     */
    public boolean is(Type type, int index) {
        int currentIndex = 0;
        for (Pair<Type, Object> packetValue : packetValues) {
            if (packetValue.getKey() == type) { // Ref check
                if (currentIndex == index) {
                    return true;
                }
                currentIndex++;
            }
        }
        return false;
    }

    /**
     * Check if a type is at an index
     *
     * @param type  The type of the part you wish to get.
     * @param index The index of the part (relative to the type)
     * @return True if the type is at the index
     */
    public boolean isReadable(Type type, int index) {
        int currentIndex = 0;
        for (Pair<Type, Object> packetValue : readableObjects) {
            if (packetValue.getKey().getBaseClass() == type.getBaseClass()) { // Ref check
                if (currentIndex == index) {
                    return true;
                }
                currentIndex++;
            }
        }
        return false;
    }


    /**
     * Set a currently existing part in the output
     *
     * @param type  The type of the part you wish to set.
     * @param <T>   The return type of the type you wish to set.
     * @param index The index of the part (relative to the type)
     * @param value The value of the part you wish to set it to.
     * @throws Exception If it fails to set it, an exception will be thrown.
     */
    public <T> void set(Type<T> type, int index, T value) throws Exception {
        int currentIndex = 0;
        for (Pair<Type, Object> packetValue : packetValues) {
            if (packetValue.getKey() == type) { // Ref check
                if (currentIndex == index) {
                    packetValue.setValue(value);
                    return;
                }
                currentIndex++;
            }
        }
        Exception e = new ArrayIndexOutOfBoundsException("Could not find type " + type.getTypeName() + " at " + index);
        throw new InformativeException(e).set("Type", type.getTypeName()).set("Index", index).set("Packet ID", getId());
    }

    /**
     * Read a type from the input.
     *
     * @param type The type you wish to read
     * @param <T>  The return type of the type you wish to read.
     * @return The requested type
     * @throws Exception If it fails to read
     */
    public <T> T read(Type<T> type) throws Exception {
        if (type == Type.NOTHING) return null;
        if (readableObjects.isEmpty()) {
            Preconditions.checkNotNull(inputBuffer, "This packet does not have an input buffer.");
            // We could in the future log input read values, but honestly for things like bulk maps, mem waste D:
            try {
                return type.read(inputBuffer);
            } catch (Exception e) {
                throw new InformativeException(e).set("Type", type.getTypeName()).set("Packet ID", getId()).set("Data", packetValues);
            }
        } else {
            Pair<Type, Object> read = readableObjects.poll();
            if (read.getKey().equals(type) || (type.getBaseClass().equals(read.getKey().getBaseClass()) && type.getOutputClass().equals(read.getKey().getOutputClass()))) {
                return (T) read.getValue();
            } else {
                if (type == Type.NOTHING) {
                    return read(type); // retry
                } else {
                    Exception e = new IOException("Unable to read type " + type.getTypeName() + ", found " + read.getKey().getTypeName());
                    throw new InformativeException(e).set("Type", type.getTypeName()).set("Packet ID", getId()).set("Data", packetValues);
                }
            }
        }
    }

    /**
     * Write a type to the output.
     *
     * @param type  The type to write.
     * @param <T>   The return type of the type you wish to write.
     * @param value The value of the type to write.
     */
    public <T> void write(Type<T> type, T value) {
        if (value != null) {
            if (!type.getOutputClass().isAssignableFrom(value.getClass())) {
                // attempt conversion
                if (type instanceof TypeConverter) {
                    value = (T) ((TypeConverter) type).from(value);
                } else {
                    System.out.println("Possible type mismatch: " + value.getClass().getName() + " -> " + type.getOutputClass());
                }
            }
        }
        packetValues.add(new Pair<Type, Object>(type, value));
    }

    /**
     * Take a value from the input and write to the output.
     *
     * @param type The type to read and write.
     * @param <T>  The return type of the type you wish to pass through.
     * @return The type which was read/written.
     * @throws Exception If it failed to read or write
     */
    public <T> T passthrough(Type<T> type) throws Exception {
        T value = read(type);
        write(type, value);
        return value;
    }

    /**
     * Take all the inputs and write them to the output.
     *
     * @throws Exception If it failed to read or write
     */
    public void passthroughAll() throws Exception {
        // Copy previous objects
        packetValues.addAll(readableObjects);
        readableObjects.clear();
        // If the buffer has readable bytes, copy them.
        if (inputBuffer.readableBytes() > 0) {
            passthrough(Type.REMAINING_BYTES);
        }
    }

    /**
     * Write the current output to a buffer.
     *
     * @param buffer The buffer to write to.
     * @throws Exception Throws an exception if it fails to write a value.
     */
    public void writeToBuffer(ByteBuf buffer) throws Exception {
        if (id != -1) {
            Type.VAR_INT.write(buffer, id);
        }
        if (readableObjects.size() > 0) {
            packetValues.addAll(readableObjects);
        }

        int index = 0;
        for (Pair<Type, Object> packetValue : packetValues) {
            try {
                Object value = packetValue.getValue();
                if (value != null) {
                    if (!packetValue.getKey().getOutputClass().isAssignableFrom(value.getClass())) {
                        // attempt conversion
                        if (packetValue.getKey() instanceof TypeConverter) {
                            value = ((TypeConverter) packetValue.getKey()).from(value);
                        } else {
                            System.out.println("Possible type mismatch: " + value.getClass().getName() + " -> " + packetValue.getKey().getOutputClass());
                        }
                    }
                }
                packetValue.getKey().write(buffer, value);
            } catch (Exception e) {
                throw new InformativeException(e).set("Index", index).set("Type", packetValue.getKey().getTypeName()).set("Packet ID", getId()).set("Data", packetValues);
            }
            index++;
        }
        writeRemaining(buffer);
    }

    /**
     * Clear the input buffer / readable objects
     */
    public void clearInputBuffer() {
        if (inputBuffer != null)
            inputBuffer.clear();
        readableObjects.clear(); // :(
    }

    /**
     * Clear the packet, used if you have to change the packet completely
     */
    public void clearPacket() {
        clearInputBuffer();
        packetValues.clear();
    }

    private void writeRemaining(ByteBuf output) {
        if (inputBuffer != null) {
            output.writeBytes(inputBuffer);
        }
    }

    /**
     * Send this packet to the associated user.
     * Be careful not to send packets twice.
     * (Sends it after current)
     *
     * @param packetProtocol - The protocol version of the packet.
     * @throws Exception if it fails to write
     */
    public void send(Class<? extends Protocol> packetProtocol) throws Exception {
        if (!isCancelled()) {
            // Apply current pipeline
            List<Protocol> protocols = new ArrayList<>(user().get(ProtocolInfo.class).getPipeline().pipes());
            // Other way if outgoing
            Collections.reverse(protocols);
            int index = 0;
            for (int i = 0; i < protocols.size(); i++) {
                if (protocols.get(i).getClass().equals(packetProtocol)) {
                    index = i + 1;
                    break;
                }
            }
            // Apply other protocols
            apply(Direction.OUTGOING, user().get(ProtocolInfo.class).getState(), index, protocols);
            // Send
            ByteBuf output = inputBuffer == null ? Unpooled.buffer() : inputBuffer.alloc().buffer();
            writeToBuffer(output);
            user().sendRawPacket(output);
        }
    }

    /**
     * Send this packet to the associated user.
     * Be careful not to send packets twice.
     * (Sends it after current)
     * <br />
     * <b>This method is no longer used, it's favoured to use send(Protocol) as it will handle the pipeline properly.</b>
     *
     * @throws Exception if it fails to write
     */
    @Deprecated
    public void send() throws Exception {
        if (!isCancelled()) {
            // Send
            ByteBuf output = inputBuffer == null ? Unpooled.buffer() : inputBuffer.alloc().buffer();
            writeToBuffer(output);
            user().sendRawPacket(output);
        }
    }

    /**
     * Create a new packet for the target of this packet.
     *
     * @param packetID The ID of the new packet
     * @return The newly created packet wrapper
     */
    public PacketWrapper create(int packetID) {
        return new PacketWrapper(packetID, null, user());
    }

    /**
     * Create a new packet with values.
     *
     * @param packetID The ID of the new packet
     * @param init     A ValueCreator to write to the packet.
     * @return The newly created packet wrapper
     * @throws Exception If it failed to write the values from the ValueCreator.
     */
    public PacketWrapper create(int packetID, ValueCreator init) throws Exception {
        PacketWrapper wrapper = create(packetID);
        init.write(wrapper);
        return wrapper;
    }

    /**
     * Applies a pipeline from an index to the wrapper
     *
     * @param direction The direction
     * @param state     The state
     * @param index     The index to start from
     * @param pipeline  The pipeline
     * @return The current packetwrapper
     * @throws Exception
     */
    public PacketWrapper apply(Direction direction, State state, int index, List<Protocol> pipeline) throws Exception {
        for (int i = index; i < pipeline.size(); i++) { // Copy to prevent from removal.
            pipeline.get(i).transform(direction, state, this);
            // Reset the reader for the packetWrapper (So it can be recycled across packets)
            resetReader();
        }

        return this;
    }

    /**
     * Cancel this packet from sending
     */
    public void cancel() {
        this.send = false;
    }

    /**
     * Check if this packet is cancelled.
     *
     * @return True if the packet won't be sent.
     */
    public boolean isCancelled() {
        return !this.send;
    }

    /**
     * Get the user associated with this Packet
     *
     * @return The user
     */
    public UserConnection user() {
        return this.userConnection;
    }

    /**
     * Reset the reader, so that it can be read again.
     */
    public void resetReader() {
        this.readableObjects.addAll(packetValues);
        this.packetValues.clear();
    }

    /**
     * Send the current packet to the server.
     * (Ensure the ID is suitable for the server version)
     *
     * @throws Exception If it failed to write
     */
    public void sendToServer() throws Exception {
        if (!isCancelled()) {
            ByteBuf output = inputBuffer == null ? Unpooled.buffer() : inputBuffer.alloc().buffer();
            Type.VAR_INT.write(output, ViaDecodeHandler.PASSTHROUGH_ID); // Pass through

            writeToBuffer(output);

            PipelineUtil.getContextBefore("decompress", user().getChannel().pipeline()).fireChannelRead(output);
        }
    }

    @Override
    public String toString() {
        return "PacketWrapper{" +
                "packetValues=" + packetValues +
                ", readableObjects=" + readableObjects +
                ", id=" + id +
                '}';
    }
}
