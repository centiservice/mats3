package io.mats3.api.intercept;

/**
 * Marker interface to denote a logging interceptor. The MatsFactory will only allow one such singleton interceptor,
 * and remove any previously installed when installing a new. The MatsFactory will install the most
 * verbose standard variant upon creation.
 *
 * @author Endre Stølsvik - 2021-02-19 13:18 - http://endre.stolsvik.com
 */
public interface MatsLoggingInterceptor {
}
