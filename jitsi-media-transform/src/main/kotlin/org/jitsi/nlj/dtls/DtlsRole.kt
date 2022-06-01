/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.nlj.dtls

import org.bouncycastle.tls.DTLSTransport

interface DtlsRole {
    /**
     * 'Starts' a DTLS connection.  Blocks until the connection
     * has been successfully established (an exception is thrown
     * upon failure).  Returns the resulting [DTLSTransport].
     *
     * The definition of 'start' varies between roles.  For a client
     * this means 'connect' to a remote server, for a server it means
     * 'accept' and wait for an incoming connection.
     */
    fun start(): DTLSTransport
}
