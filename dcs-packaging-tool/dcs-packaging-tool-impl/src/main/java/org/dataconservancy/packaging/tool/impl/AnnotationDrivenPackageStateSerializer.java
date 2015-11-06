/*
 *
 *  * Copyright 2015 Johns Hopkins University
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package org.dataconservancy.packaging.tool.impl;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.dataconservancy.packaging.tool.model.PackageState;
import org.dataconservancy.packaging.tool.model.ser.Serialize;
import org.dataconservancy.packaging.tool.model.ser.StreamId;
import org.dataconservancy.packaging.tool.ser.PackageStateSerializer;
import org.dataconservancy.packaging.tool.ser.StreamMarshaller;
import org.springframework.beans.BeanUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.oxm.Marshaller;

import javax.xml.transform.stream.StreamResult;
import java.beans.PropertyDescriptor;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

/**
 * Responsible for (de)serializing <em>specified</em> fields of {@link PackageState}.  Callers may request all
 * annotated fields to be serialized by calling {@code serialize(PackageState, OutputStream)}, or callers may specify
 * a particular stream of content to be serialized by calling {@code serialize(PackageState, StreamId, OutputStream)}.
 * <p>
 * In order for fields of {@code PackageState} to be serialized, they must be annotated with
 * {@link org.dataconservancy.packaging.tool.model.ser.Serialize}, and have a so-called "stream identifier" assigned.
 * Therefore, this interface <em>does not serialize the entirety</em> of a {@code PackageState} instance; it will only
 * serialize fields that have been annotated, and that have standard JavaBean accessors and mutators
 * (getXXX and setXXX methods).
 * </p>
 * <p>
 * Example usage: Serializing package metadata to a file
 * </p>
 * <pre>
 *     // Assume the existence of a populated PackageState instance
 *     PackageState state = ...
 *
 *     // Instantiate and configure the serializer, or have it dependency injected
 *     AnnotationDrivenPackageStateSerializer serializer = new AnnotationDrivenPackageStateSerializer();
 *     serializer.setArchive(false)
 *
 *     File packageMd = new File("packageMetadata.out");
 *     serializer.serialize(state, StreamId.PACKAGE_METADATA, new FileOutputStream(packageMd));
 * </pre>
 * <p>
 * Example usage: Serializing package state before closing the Package Tool GUI
 * </p>
 * <pre>
 *     // Assume the existence of a populated PackageState instance
 *     PackageState state = ...
 *
 *     // Instantiate and configure the serializer, or have it dependency injected
 *     AnnotationDrivenPackageStateSerializer serializer = new AnnotationDrivenPackageStateSerializer();
 *     serializer.setArchive(true)
 *
 *     File myPackage = new File("mypackage.zip");
 *     serializer.serialize(state, new FileOutputStream(packageMd));
 * </pre>
 */
public class AnnotationDrivenPackageStateSerializer implements PackageStateSerializer {

    /**
     * Placeholders: requested stream id, PackageState class name, list of possible stream ids from the PackageState
     * class (obtained at runtime)
     */
    private static final String ERR_INVALID_STREAMID = "Unable to obtain streamId '%s' from the '%s' class.  The " +
            "streamId may be invalid, or the expected JavaBean accessor method is missing or not accessible.  " +
            "Allowed stream identifiers are: %s.";

    /**
     * Placeholders: method name, class name, error message
     */
    private static final String ERR_INVOKING_METHOD = "Error invoking the method named '%s' on an instance of '%s': %s";

    /**
     * Placeholders: streamId
     */
    private static final String ERR_MISSING_STREAMMARSHALLER = "No StreamMarshaller found for streamId '%s'.  Check " +
            "the 'marshallerMap' supplied to setMarshallerMap(...).";

    /**
     * Placeholders: streamId, streamId
     */
    private static final String ERR_MISSING_SPRINGMARSHALLER = "No Spring Marshaller found for streamId '%s'.  Check " +
            "the StreamMarshaller for streamId '%s' in the 'marshallerMap' supplied to setMarshallerMap(...).";

    /**
     * Placeholders: streamId, error message
     */
    private static final String ERR_MARSHALLING_STREAM = "Error marshalling streamId '%s': %s";

//    public enum CompressionFormat {
//        ZIP,
//        TAR
//    }
//
//    private CompressionFormat compressionFormat = CompressionFormat.ZIP;

    private boolean archive = true;

    private String compressionFormat = org.apache.commons.compress.archivers.ArchiveStreamFactory.ZIP;

    private boolean compress = true;

    private ArchiveStreamFactory arxStreamFactory;

    private Map<StreamId, StreamMarshaller> marshallerMap;

    private Map<StreamId, PropertyDescriptor> propertyDescriptors = getStreamDescriptors();

    @Override
    public void deserialize(PackageState state, InputStream in) {
    }

    @Override
    public void serialize(PackageState state, OutputStream out) {

        // If we are archiving, create an ArchiveOutputStream and serialize each stream from the PackageState
        // to the ArchiveOutputStream.
        //
        // If we aren't archiving, simply serialize each stream from the PackageState to the supplied OutputStream.

        if (archive) {
            try (ArchiveOutputStream aos = arxStreamFactory.newArchiveOutputStream(out)) {
                propertyDescriptors.keySet().stream().forEach(streamId -> {
                    try {
                        serializeToArchive(state, streamId, aos);
                    } catch (IOException e) {
                        throw new RuntimeException(e.getMessage(), e);
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        } else {
            propertyDescriptors.keySet().stream().forEach(streamId -> {
                StreamResult result = new StreamResult(out);
                serializeToResult(state, streamId, result);
            });
        }
    }

    @Override
    public void serialize(PackageState state, StreamId streamId, OutputStream out) {

        // If we are archiving, create an ArchiveOutputStream and serialize the specified stream from the PackageState
        // to the ArchiveOutputStream.
        //
        // If we aren't archiving, simply serialize the specified stream from the PackageState to the supplied
        // OutputStream.

        if (archive) {
            try (ArchiveOutputStream aos = arxStreamFactory.newArchiveOutputStream(out)) {
                serializeToArchive(state, streamId, aos);
            } catch (IOException e) {
                throw new RuntimeException(String.format(ERR_MARSHALLING_STREAM, streamId, e.getMessage()), e);
            }
        } else {
            StreamResult result = new StreamResult(out);
            serializeToResult(state, streamId, result);
        }
    }

    /**
     * Serializes the identified stream from the package state to the supplied archive output stream.
     *
     * @param state the package state object containing the identified stream
     * @param streamId the stream identifier for the content being serialized
     * @param aos the archive output stream to serialize the stream to
     * @throws IOException
     */
    void serializeToArchive(PackageState state, StreamId streamId, ArchiveOutputStream aos) throws IOException {

        // when writing to an archive file:
        //   1. read the stream to be serialized, to get its properties
        //   2. create the archive entry using the properties and write it to the archive stream
        //   3. write the stream to be serialized to the archive stream
        //   4. close the entry

        final FileTime now = FileTime.fromMillis(Calendar.getInstance().getTimeInMillis());

        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024 * 4);
        CRC32CalculatingOutputStream crc = new CRC32CalculatingOutputStream(baos);
        StreamResult result = new StreamResult(crc);
        serializeToResult(state, streamId, result);
        ArchiveEntry arxEntry = arxStreamFactory
                .newArchiveEntry(streamId.name(), baos.size(), now, now, 0644, crc.reset());

        try {
            aos.putArchiveEntry(arxEntry);
            baos.writeTo(aos);
            aos.closeArchiveEntry();
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Serializes the identified stream from the package state to the supplied result.
     *
     * @param state the package state object containing the identified stream
     * @param streamId the stream identifier for the content being serialized
     * @param result holds the output stream for the serialization result
     */
    void serializeToResult(PackageState state, StreamId streamId, StreamResult result) {

        PropertyDescriptor pd = propertyDescriptors.get(streamId);

        if (pd == null) {
            throw new IllegalArgumentException(String.format(ERR_INVALID_STREAMID, streamId.name(),
                    PackageState.class.getName(),
                    propertyDescriptors.keySet().stream().map(Enum::name).collect(Collectors.joining(", "))));
        }

        Object toSerialize = null;

        try {
            toSerialize = pd.getReadMethod().invoke(state);
        } catch (Exception e) {
            String err = String.format(ERR_INVOKING_METHOD,
                    pd.getReadMethod(), state.getClass().getName(), e.getMessage());
            throw new RuntimeException(err, e);
        }

        try {
            StreamMarshaller streamMarshaller = marshallerMap.get(streamId);
            if (streamMarshaller == null) {
                throw new RuntimeException(String.format(ERR_MISSING_STREAMMARSHALLER, streamId));
            }

            Marshaller marshaller = streamMarshaller.getMarshaller();
            if (marshaller == null) {
                throw new RuntimeException(String.format(ERR_MISSING_SPRINGMARSHALLER, streamId, streamId));
            }

            marshaller.marshal(toSerialize, result);
        } catch (Exception e) {
            throw new RuntimeException(String.format(ERR_MARSHALLING_STREAM, streamId, e.getMessage()), e);
        }
    }

    @Override
    public void deserialize(PackageState state, InputStream in, StreamId streamId) {
        // TODO
    }

    public boolean isCompress() {
        return compress;
    }

    public void setCompress(boolean compress) {
        this.compress = compress;
    }

    public String getCompressionFormat() {
        return compressionFormat;
    }

    public void setCompressionFormat(String compressionFormat) {
        this.compressionFormat = compressionFormat;
    }

    public Map<StreamId, StreamMarshaller> getMarshallerMap() {
        return marshallerMap;
    }

    public void setMarshallerMap(Map<StreamId, StreamMarshaller> marshallerMap) {
        this.marshallerMap = marshallerMap;
    }

    public ArchiveStreamFactory getArxStreamFactory() {
        return arxStreamFactory;
    }

    public void setArxStreamFactory(ArchiveStreamFactory arxStreamFactory) {
        this.arxStreamFactory = arxStreamFactory;
    }

    public boolean isArchive() {
        return archive;
    }

    public void setArchive(boolean archive) {
        this.archive = archive;
    }

    /**
     * Answers a {@code Map} of {@link PropertyDescriptor} instances, which are used to reflectively access the
     * {@link Serialize serializable} streams on {@link PackageState} instances.
     * <p>
     * Use of {@code PropertyDescriptor} is simply a convenience in lieu of the use of underlying Java reflection.
     * </p>
     * <p>
     * This method looks for fields annotated by the {@code Serialize} annotation on the {@code PackageState.class}.
     * A {@code PropertyDescriptor} is created for each field, and is keyed by the {@code StreamId} in the returned
     * {@code Map}.
     * </p>
     *
     * @return a Map of PropertyDescriptors keyed by their StreamId.
     */
    static Map<StreamId, PropertyDescriptor> getStreamDescriptors() {
        HashMap<StreamId, PropertyDescriptor> results = new HashMap<>();

        Arrays.stream(PackageState.class.getDeclaredFields())
                .filter(candidateField -> AnnotationUtils.getAnnotation(candidateField, Serialize.class) != null)
                .forEach(annotatedField -> {
                    AnnotationAttributes attributes = AnnotationUtils.getAnnotationAttributes(annotatedField,
                            AnnotationUtils.getAnnotation(annotatedField, Serialize.class));
                    StreamId streamId = (StreamId) attributes.get("streamId");
                    PropertyDescriptor descriptor =
                            BeanUtils.getPropertyDescriptor(PackageState.class, annotatedField.getName());
                    results.put(streamId, descriptor);
                });

        return results;
    }

    private class CRC32CalculatingOutputStream extends FilterOutputStream {

        private CRC32 crc32 = new CRC32();

        public CRC32CalculatingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void close() throws IOException {
            out.close();
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void write(int b) throws IOException {
            crc32.update(b);
            out.write(b);
        }

        @Override
        public void write(byte[] b) throws IOException {
            crc32.update(b);
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            crc32.update(b, off, len);
            out.write(b, off, len);
        }

        long reset() {
            long crc = crc32.getValue();
            crc32.reset();
            return crc;
        }

    }

}