package io.mats3.serial.json;

import java.util.zip.Deflater;

/**
 * <b>Deprecated</b> - currently just present to point to new name {@link MatsSerializerJson}, directly extending it.
 *
 * TODO: Delete once everybody >= 0.16.0
 *
 * @author Endre Stølsvik - 2020-11-15 - http://endre.stolsvik.com
 */
public class MatsSerializer_DefaultJson extends MatsSerializerJson {

    /**
     * <b>Deprecated</b>. Constructs a MatsSerializer, using the specified Compression Level - refer to
     * {@link Deflater}'s constants and levels.
     *
     * @param compressionLevel
     *            the compression level given to {@link Deflater} to use.
     * @deprecated use {@link MatsSerializerJson#create(int)}
     */
    @Deprecated
    public MatsSerializer_DefaultJson(int compressionLevel) {
        super(compressionLevel);
    }

    /**
     * <b>Deprecated</b>. Constructs a MatsSerializer, using the {@link #DEFAULT_COMPRESSION_LEVEL} (which is
     * {@link Deflater#BEST_SPEED}, which is 1).
     *
     * @deprecated use {@link MatsSerializerJson#create()}
     */
    @Deprecated
    public MatsSerializer_DefaultJson() {
        this(DEFAULT_COMPRESSION_LEVEL);
    }
}
