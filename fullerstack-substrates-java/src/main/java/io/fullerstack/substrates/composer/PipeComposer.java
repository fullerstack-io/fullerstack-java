package io.fullerstack.substrates.composer;

import io.humainary.substrates.api.Substrates.*;

/**
 * Composer that transforms Channel<E> into Pipe<E>.
 *
 * <p>This is the standard composer used for creating typed pipes from channels.
 * Delegates to Channel.pipe() to obtain the configured Pipe instance.
 *
 * @param <E> the emission type
 */
public class PipeComposer<E> implements Composer<Pipe<E>, E> {

    @Override
    public Pipe<E> compose(Channel<E> channel) {
        return channel.pipe();
    }

    /**
     * Factory method for creating a PipeComposer.
     *
     * @param emissionClass the emission class (used for type inference)
     * @param <E> the emission type
     * @return new PipeComposer instance
     */
    public static <E> Composer<Pipe<E>, E> create(Class<E> emissionClass) {
        return new PipeComposer<>();
    }
}
