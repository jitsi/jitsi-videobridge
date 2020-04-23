The REST API allows querying Videobridge whether it deems itself in a healthy state (i.e. the application is operational and the functionality it provides should perform as expected) at the time of the query or not. Videobridge performs periodic internal tests, and returns the latest result in response to requests to the `/about/health` endpoint.

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
        <li><code>200 OK</code> The service is healthy. The response is with <code>Content-Type: application/json</code> but no JSON value is provided i.e. <code>Content-Length: 0</code>.</li>
        <li><code>500 Internal Server Error</code> or any other <code>5xx</code> status code if Videobridge determined that it is in an unhealthy state.</li>
      </ul>
    </td>
  </tr>
</table>

To enable the REST API, start Videobridge with the command line argument <code>--apis=rest</code> (or <code>--apis=rest,xmpp</code> to enable the XMPP API as well).
