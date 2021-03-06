package com.airbnb.plog.client.fragmentation;

import com.airbnb.plog.Message;
import com.airbnb.plog.common.Murmur3;
import com.google.common.base.Charsets;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteOrder;
import java.util.Collection;

@Slf4j
public class Fragmenter {
    public static final byte[] UDP_V0_FRAGMENT_PREFIX = new byte[]{0, 1};
    private static final int HEADER_SIZE = 24;
    private final int maxFragmentSizeExcludingHeader;

    public Fragmenter(int maxFragmentSize) {
        maxFragmentSizeExcludingHeader = maxFragmentSize - HEADER_SIZE;
        if (maxFragmentSizeExcludingHeader < 1)
            throw new IllegalArgumentException("Fragment size < " + (HEADER_SIZE + 1));
    }

    public ByteBuf[] fragment(ByteBufAllocator alloc, byte[] payload, Collection<String> tags, int messageIndex) {
        final ByteBuf buf = Unpooled.wrappedBuffer(payload);
        final int hash = Murmur3.hash32(buf, 0, payload.length);
        return fragment(alloc, buf, tags, messageIndex, payload.length, hash);
    }

    public ByteBuf[] fragment(ByteBufAllocator alloc, ByteBuf payload, Collection<String> tags, int messageIndex) {
        final int length = payload.readableBytes();
        final int hash = Murmur3.hash32(payload, 0, length);
        return fragment(alloc, payload, tags, messageIndex, length, hash);
    }

    public ByteBuf[] fragment(ByteBufAllocator alloc, Message msg, int messageIndex) {
        return fragment(alloc, msg.content(), msg.getTags(), messageIndex);
    }

    public ByteBuf[] fragment(ByteBufAllocator alloc, ByteBuf payload, Collection<String> tags, int messageIndex, int length, int hash) {
        final byte[][] tagBytes;

        int tagsBufferLength = 0;

        final int tagsCount;
        if (tags != null && !tags.isEmpty()) {
            tagsCount = tags.size();
            if (tagsCount > 1)
                tagsBufferLength += tagsCount - 1;
            tagBytes = new byte[tagsCount][];
            int tagIdx = 0;
            for (String tag : tags) {
                final byte[] bytes = tag.getBytes(Charsets.UTF_8);
                tagsBufferLength += bytes.length;
                tagBytes[tagIdx] = bytes;
                tagIdx++;
            }

            if (tagBytes.length > maxFragmentSizeExcludingHeader)
                throw new IllegalStateException("Cannot store " + tagBytes.length + " bytes of tags in " +
                        maxFragmentSizeExcludingHeader + " bytes max");
        } else {
            tagBytes = null;
            tagsCount = 0;
        }

        // round-up division
        final int fragmentCount = (int) (
                ((long) length + tagsBufferLength + maxFragmentSizeExcludingHeader - 1)
                        / maxFragmentSizeExcludingHeader);

        final ByteBuf[] fragments = new ByteBuf[fragmentCount];

        // All packets but the last are easy
        int contentIdx, fragmentIdx;
        for (contentIdx = 0, fragmentIdx = 0; fragmentIdx < fragmentCount - 1;
             fragmentIdx++, contentIdx += maxFragmentSizeExcludingHeader) {
            final ByteBuf fragment = alloc.buffer(HEADER_SIZE + maxFragmentSizeExcludingHeader,
                    HEADER_SIZE + maxFragmentSizeExcludingHeader).order(ByteOrder.BIG_ENDIAN);
            writeHeader(messageIndex, maxFragmentSizeExcludingHeader, 0, length, hash, fragmentCount, fragmentIdx, fragment);
            fragment.writeBytes(payload, contentIdx, maxFragmentSizeExcludingHeader);
            fragments[fragmentIdx] = fragment;
        }

        final int lastPayloadLength = length - (maxFragmentSizeExcludingHeader * (fragmentCount - 1));
        final ByteBuf finalFragment = alloc.buffer(HEADER_SIZE + tagsBufferLength + lastPayloadLength,
                HEADER_SIZE + tagsBufferLength + lastPayloadLength).order(ByteOrder.BIG_ENDIAN);
        writeHeader(messageIndex, maxFragmentSizeExcludingHeader, tagsBufferLength, length, hash, fragmentCount, fragmentIdx, finalFragment);

        if (tagsCount > 0) {
            finalFragment.setShort(20, tagsBufferLength); // tags buffer length
            for (int i = 0; i < tagsCount - 1; i++) {
                finalFragment.writeBytes(tagBytes[i]);
                finalFragment.writeZero(1);
            }
            finalFragment.writeBytes(tagBytes[tagsCount - 1]);
        }
        finalFragment.writeBytes(payload, contentIdx, lastPayloadLength);
        fragments[fragmentCount - 1] = finalFragment;

        return fragments;
    }

    private static void writeHeader(int messageIndex, int fragmentLength, int tagsBufferLength, int messageLength, int hash, int fragmentCount, int fragmentIdx, ByteBuf fragment) {
        fragment.writeBytes(UDP_V0_FRAGMENT_PREFIX);
        fragment.writeShort(fragmentCount);
        fragment.writeShort(fragmentIdx);
        fragment.writeShort(fragmentLength);
        fragment.writeInt(messageIndex);
        fragment.writeInt(messageLength);
        fragment.writeInt(hash);
        fragment.writeShort(tagsBufferLength);
        fragment.writeZero(2);
    }
}
