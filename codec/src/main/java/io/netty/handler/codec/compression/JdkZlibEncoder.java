/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.compression;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static java.util.Objects.requireNonNull;

/**
 * Compresses a {@link ByteBuf} using the deflate algorithm.
 */
public class JdkZlibEncoder extends CompressionHandler {

    private static final class JdkZlibCompressor implements Compressor {
        private final ZlibWrapper wrapper;
        private final Deflater deflater;

        /*
         * GZIP support
         */
        private final CRC32 crc = new CRC32();
        private static final byte[] gzipHeader = {0x1f, (byte) 0x8b, Deflater.DEFLATED, 0, 0, 0, 0, 0, 0, 0};
        private boolean writeHeader = true;
        private boolean finished;

        /**
         * Creates a new zlib encoder with the default compression level ({@code 6})
         * and the default wrapper ({@link ZlibWrapper#ZLIB}).
         *
         * @throws CompressionException if failed to initialize zlib
         */
        JdkZlibCompressor() {
            this(6);
        }

        /**
         * Creates a new zlib encoder with the specified {@code compressionLevel}
         * and the default wrapper ({@link ZlibWrapper#ZLIB}).
         *
         * @param compressionLevel
         *        {@code 1} yields the fastest compression and {@code 9} yields the
         *        best compression.  {@code 0} means no compression.  The default
         *        compression level is {@code 6}.
         *
         * @throws CompressionException if failed to initialize zlib
         */
        JdkZlibCompressor(int compressionLevel) {
            this(ZlibWrapper.ZLIB, compressionLevel);
        }

        /**
         * Creates a new zlib encoder with the default compression level ({@code 6})
         * and the specified wrapper.
         *
         * @throws CompressionException if failed to initialize zlib
         */
        JdkZlibCompressor(ZlibWrapper wrapper) {
            this(wrapper, 6);
        }

        JdkZlibCompressor(ZlibWrapper wrapper, int compressionLevel) {
            if (compressionLevel < 0 || compressionLevel > 9) {
                throw new IllegalArgumentException(
                        "compressionLevel: " + compressionLevel + " (expected: 0-9)");
            }
            requireNonNull(wrapper, "wrapper");
            if (wrapper == ZlibWrapper.ZLIB_OR_NONE) {
                throw new IllegalArgumentException(
                        "wrapper '" + ZlibWrapper.ZLIB_OR_NONE + "' is not " +
                                "allowed for compression.");
            }

            this.wrapper = wrapper;
            deflater = new Deflater(compressionLevel, wrapper != ZlibWrapper.ZLIB);
        }

        /**
         * Creates a new zlib encoder with the default compression level ({@code 6})
         * and the specified preset dictionary.  The wrapper is always
         * {@link ZlibWrapper#ZLIB} because it is the only format that supports
         * the preset dictionary.
         *
         * @param dictionary  the preset dictionary
         *
         * @throws CompressionException if failed to initialize zlib
         */
        JdkZlibCompressor(byte[] dictionary) {
            this(6, dictionary);
        }

        /**
         * Creates a new zlib encoder with the specified {@code compressionLevel}
         * and the specified preset dictionary.  The wrapper is always
         * {@link ZlibWrapper#ZLIB} because it is the only format that supports
         * the preset dictionary.
         *
         * @param compressionLevel
         *        {@code 1} yields the fastest compression and {@code 9} yields the
         *        best compression.  {@code 0} means no compression.  The default
         *        compression level is {@code 6}.
         * @param dictionary  the preset dictionary
         *
         * @throws CompressionException if failed to initialize zlib
         */
        JdkZlibCompressor(int compressionLevel, byte[] dictionary) {
            if (compressionLevel < 0 || compressionLevel > 9) {
                throw new IllegalArgumentException(
                        "compressionLevel: " + compressionLevel + " (expected: 0-9)");
            }
            requireNonNull(dictionary, "dictionary");

            wrapper = ZlibWrapper.ZLIB;
            deflater = new Deflater(compressionLevel);
            deflater.setDictionary(dictionary);
        }

        @Override
        public ByteBuf compress(ByteBuf uncompressed, ByteBufAllocator allocator) throws CompressionException {
            if (finished) {
                return Unpooled.EMPTY_BUFFER;
            }

            int len = uncompressed.readableBytes();
            if (len == 0) {
                return Unpooled.EMPTY_BUFFER;
            }

            int offset;
            byte[] inAry;
            if (uncompressed.hasArray()) {
                // if it is backed by an array we not need to to do a copy at all
                inAry = uncompressed.array();
                offset = uncompressed.arrayOffset() + uncompressed.readerIndex();
                // skip all bytes as we will consume all of them
                uncompressed.skipBytes(len);
            } else {
                inAry = new byte[len];
                uncompressed.readBytes(inAry);
                offset = 0;
            }

            int sizeEstimate = (int) Math.ceil(len * 1.001) + 12;
            if (writeHeader) {
                switch (wrapper) {
                    case GZIP:
                        sizeEstimate += gzipHeader.length;
                        break;
                    case ZLIB:
                        sizeEstimate += 2; // first two magic bytes
                        break;
                    default:
                        // no op
                }
            }
            ByteBuf out = allocator.buffer(sizeEstimate);
            try {
                if (writeHeader) {
                    writeHeader = false;
                    if (wrapper == ZlibWrapper.GZIP) {
                        out.writeBytes(gzipHeader);
                    }
                }

                if (wrapper == ZlibWrapper.GZIP) {
                    crc.update(inAry, offset, len);
                }

                deflater.setInput(inAry, offset, len);
                for (;;) {
                    deflate(out);
                    if (deflater.needsInput()) {
                        // Consumed everything
                        break;
                    } else {
                        if (!out.isWritable()) {
                            // We did not consume everything but the buffer is not writable anymore. Increase the
                            // capacity to make more room.
                            out.ensureWritable(out.writerIndex());
                        }
                    }
                }
                return out;
            } catch (Throwable cause) {
                out.release();
                throw cause;
            }
        }

        @Override
        public ByteBuf finish(ByteBufAllocator allocator) {
            if (finished) {
                return Unpooled.EMPTY_BUFFER;
            }

            finished = true;
            ByteBuf footer = allocator.heapBuffer();
            try {
                if (writeHeader && wrapper == ZlibWrapper.GZIP) {
                    // Write the GZIP header first if not written yet. (i.e. user wrote nothing.)
                    writeHeader = false;
                    footer.writeBytes(gzipHeader);
                }

                deflater.finish();

                while (!deflater.finished()) {
                    deflate(footer);
                }
                if (wrapper == ZlibWrapper.GZIP) {
                    int crcValue = (int) crc.getValue();
                    int uncBytes = deflater.getTotalIn();
                    footer.writeByte(crcValue);
                    footer.writeByte(crcValue >>> 8);
                    footer.writeByte(crcValue >>> 16);
                    footer.writeByte(crcValue >>> 24);
                    footer.writeByte(uncBytes);
                    footer.writeByte(uncBytes >>> 8);
                    footer.writeByte(uncBytes >>> 16);
                    footer.writeByte(uncBytes >>> 24);
                }
                deflater.end();
                return footer;
            } catch (Throwable cause) {
                footer.release();
                throw cause;
            }
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public void close() {
            finished = true;
        }

        private void deflate(ByteBuf out) {
            if (out.hasArray()) {
                int numBytes;
                do {
                    int writerIndex = out.writerIndex();
                    numBytes = deflater.deflate(
                            out.array(), out.arrayOffset() + writerIndex, out.writableBytes(), Deflater.SYNC_FLUSH);
                    out.writerIndex(writerIndex + numBytes);
                } while (numBytes > 0);
            } else if (out.nioBufferCount() == 1) {
                // Use internalNioBuffer because nioBuffer is allowed to copy,
                // which is fine for reading but not for writing.
                int numBytes;
                do {
                    int writerIndex = out.writerIndex();
                    ByteBuffer buffer = out.internalNioBuffer(writerIndex, out.writableBytes());
                    numBytes = deflater.deflate(buffer, Deflater.SYNC_FLUSH);
                    out.writerIndex(writerIndex + numBytes);
                } while (numBytes > 0);
            } else {
                throw new IllegalArgumentException(
                        "Don't know how to deflate buffer without array or NIO buffer count of 1: " + out);
            }
        }
    }

    /**
     * Creates a new zlib encoder with the default compression level ({@code 6})
     * and the default wrapper ({@link ZlibWrapper#ZLIB}).
     *
     * @throws CompressionException if failed to initialize zlib
     */
    public JdkZlibEncoder() {
        this(6);
    }

    /**
     * Creates a new zlib encoder with the specified {@code compressionLevel}
     * and the default wrapper ({@link ZlibWrapper#ZLIB}).
     *
     * @param compressionLevel
     *        {@code 1} yields the fastest compression and {@code 9} yields the
     *        best compression.  {@code 0} means no compression.  The default
     *        compression level is {@code 6}.
     *
     * @throws CompressionException if failed to initialize zlib
     */
    public JdkZlibEncoder(int compressionLevel) {
        this(ZlibWrapper.ZLIB, compressionLevel);
    }

    /**
     * Creates a new zlib encoder with the default compression level ({@code 6})
     * and the specified wrapper.
     *
     * @throws CompressionException if failed to initialize zlib
     */
    public JdkZlibEncoder(ZlibWrapper wrapper) {
        this(wrapper, 6);
    }

    /**
     * Creates a new zlib encoder with the specified {@code compressionLevel}
     * and the specified wrapper.
     *
     * @param compressionLevel
     *        {@code 1} yields the fastest compression and {@code 9} yields the
     *        best compression.  {@code 0} means no compression.  The default
     *        compression level is {@code 6}.
     *
     * @throws CompressionException if failed to initialize zlib
     */
    public JdkZlibEncoder(ZlibWrapper wrapper, int compressionLevel) {
        super(() -> new JdkZlibCompressor(wrapper, compressionLevel));
    }

    /**
     * Creates a new zlib encoder with the default compression level ({@code 6})
     * and the specified preset dictionary.  The wrapper is always
     * {@link ZlibWrapper#ZLIB} because it is the only format that supports
     * the preset dictionary.
     *
     * @param dictionary  the preset dictionary
     *
     * @throws CompressionException if failed to initialize zlib
     */
    public JdkZlibEncoder(byte[] dictionary) {
        this(6, dictionary);
    }

    /**
     * Creates a new zlib encoder with the specified {@code compressionLevel}
     * and the specified preset dictionary.  The wrapper is always
     * {@link ZlibWrapper#ZLIB} because it is the only format that supports
     * the preset dictionary.
     *
     * @param compressionLevel
     *        {@code 1} yields the fastest compression and {@code 9} yields the
     *        best compression.  {@code 0} means no compression.  The default
     *        compression level is {@code 6}.
     * @param dictionary  the preset dictionary
     *
     * @throws CompressionException if failed to initialize zlib
     */
    public JdkZlibEncoder(int compressionLevel, byte[] dictionary) {
        super(() -> new JdkZlibCompressor(compressionLevel, dictionary));
    }
}
