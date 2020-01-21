The REST API allows querying Videobridge whether it deems itself in a healthy state (i.e. the application is operational and the functionality it provides should perform as expected) at the time of the query or not. Videorbidge will run an internal test in response to the request to determine its current health status.

<table>
  <tr>
    <th>HTTP Method</th>
    <th>Resource</th>
    <th>Response</th>
  </tr>
  <tr>
    <td><code>GET</code></td>
    <td><code>/about/health</code></td>
    <td>
      <ul>
        <li><code>200 OK</code> if Videobridge ran its internal test to determine its current health status and the test completed successfully. The response is with <code>Content-Type: application/json</code> but no JSON value is provided i.e. <code>Content-Length: 0</code>.</li>
        <li><code>503 Service Unavailable</code> if Videobridge is currently unable to run its health check. The reasons for the failure include (but are not limited to):
          <ul>
            <li>The REST API server has been brought up but the core conference-related functionality of Videobridge is still initializing. Videbridge could be considered healthy if the client chooses to interpret the HTTP status in such a fashion since the application may pass though the described transitional state under normal operation.</li>
            <li>The core conference-related functionality of Videobridge has commenced a shutdown procedure but the REST API server has not shut down yet. Videbridge could be considered healthy if the client chooses to interpret the HTTP status in such a fashion since the application may pass though the described transitional state under normal operation.</li>
            <li>The core conference-related functionality of Videobridge is in an unhealthy state and the REST API server is operating as expected.</li>
          </ul>
        </li>
        <li><code>500 Internal Server Error</code> or any other <code>5xx</code> status code if Videobridge ran its health check and determined that it is in an unhealthy state.</li>
        <li>No response (within a client-defined time frame) if Videobridge has entered a deadlock, an infinite loop, or a similar condition which prevents the completion of the health check. The server is to be considered in an unhealthy state by the client.</li>
      </ul>
    </td>
  </tr>
</table>

To enable the REST API, start Videobridge with the command line argument <code>--apis=rest</code> (or <code>--apis=rest,xmpp</code> to enable the XMPP API as well).
