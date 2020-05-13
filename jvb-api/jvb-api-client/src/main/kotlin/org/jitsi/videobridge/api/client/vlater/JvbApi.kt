// /*
//  * Copyright @ 2018 - present 8x8, Inc.
//  *
//  * Licensed under the Apache License, Version 2.0 (the "License");
//  * you may not use this file except in compliance with the License.
//  * You may obtain a copy of the License at
//  *
//  *     http://www.apache.org/licenses/LICENSE-2.0
//  *
//  * Unless required by applicable law or agreed to in writing, software
//  * distributed under the License is distributed on an "AS IS" BASIS,
//  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  * See the License for the specific language governing permissions and
//  * limitations under the License.
//  */
//
// @file:JvmName("JvbApi")
// @file:Suppress("unused")
//
// package org.jitsi.videobridge.api.client.vlater
//
// import io.ktor.client.HttpClient
// import io.ktor.client.engine.apache.Apache
// import io.ktor.client.features.HttpTimeout
// import io.ktor.client.features.json.JacksonSerializer
// import io.ktor.client.features.json.JsonFeature
// import io.ktor.client.request.patch
// import io.ktor.client.request.post
// import io.ktor.http.ContentType
// import io.ktor.http.contentType
// import kotlinx.coroutines.runBlocking
// import org.jitsi.videobridge.api.types.vlater.ConferenceCreateRequest
// import org.jitsi.videobridge.api.types.vlater.ConferenceCreateResponse
// import org.jitsi.videobridge.api.types.vlater.EndpointCreateRequest
// import org.jitsi.videobridge.api.types.vlater.EndpointCreateResponse
// import org.jitsi.videobridge.api.types.vlater.EndpointModifyRequest
// import org.jitsi.videobridge.api.types.vlater.IceRole
//
// fun createConference(id: String, name: String): ConferenceCreateResponse {
//     val client = HttpClient(Apache) {
//         install(JsonFeature) {
//             this.serializer = JacksonSerializer()
//         }
//         install(HttpTimeout) {
//             this.requestTimeoutMillis = 5000
//         }
//     }
//     val req = ConferenceCreateRequest(name)
//     return runBlocking {
//         client.post<ConferenceCreateResponse>("http://127.0.0.1:9090/v1/conferences/$id") {
//             contentType(ContentType.Application.Json)
//             body = req
//         }
//     }
// }
//
// fun createEndpoint(confId: String, epId: String, iceRole: IceRole): EndpointCreateResponse {
//     val client = HttpClient(Apache) {
//         install(JsonFeature) {
//             this.serializer = JacksonSerializer()
//         }
//         install(HttpTimeout) {
//             this.requestTimeoutMillis = 5000
//         }
//     }
//     val req = EndpointCreateRequest(iceRole)
//     return runBlocking {
//         println("posting $confId $epId")
//         client.post<EndpointCreateResponse>("http://127.0.0.1:9090/v1/conferences/$confId/endpoints/$epId") {
//             contentType(ContentType.Application.Json)
//             body = req
//         }
//     }
// }
//
// fun modifyEndpoint(confId: String, epId: String, endpointInfo: EndpointModifyRequest) {
//     val client = HttpClient(Apache) {
//         install(JsonFeature) {
//             this.serializer = JacksonSerializer()
//         }
//         install(HttpTimeout) {
//             this.requestTimeoutMillis = 5000
//         }
//     }
//     runBlocking {
//         println("posting $confId $epId")
//         client.patch<Unit>("http://127.0.0.1:9090/v1/conferences/$confId/endpoints/$epId") {
//             contentType(ContentType.Application.Json)
//             body = endpointInfo
//         }
//     }
// }
//
// fun main() {
//     val resp = createConference("2323", "my_conf")
//     println(createEndpoint(resp.id, "3232", IceRole.CONTROLLING))
// }
