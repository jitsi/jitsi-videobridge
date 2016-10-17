<%--
 _ Copyright @ 2015 Atlassian Pty Ltd
 _
 _ Licensed under the Apache License, Version 2.0 (the "License");
 _ you may not use this file except in compliance with the License.
 _ You may obtain a copy of the License at
 _
 _     http://www.apache.org/licenses/LICENSE-2.0
 _
 _ Unless required by applicable law or agreed to in writing, software
 _ distributed under the License is distributed on an "AS IS" BASIS,
 _ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 _ See the License for the specific language governing permissions and
 _ limitations under the License.
--%>
<%@ page import="org.jitsi.videobridge.openfire.*" %>
<%@ page import="org.jivesoftware.openfire.*" %>
<%@ page import="org.jivesoftware.util.*" %>
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib uri="http://java.sun.com/jstl/core_rt" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jstl/fmt_rt" prefix="fmt" %>
<%

    boolean update = request.getParameter("update") != null;
    String errorMessage = null;

    // Get handle on the plugin
    PluginImpl plugin = (PluginImpl) XMPPServer.getInstance()
        .getPluginManager().getPlugin("jitsivideobridge");

    if (update)
    {
        String minPort = request.getParameter("minport");
        if (minPort != null) {
            minPort = minPort.trim();
            try
            {
                int port = Integer.valueOf(minPort);

                if(port >= 1 && port <= 65535)
                    JiveGlobals.setProperty(
                        PluginImpl.MIN_PORT_NUMBER_PROPERTY_NAME, minPort);
                else
                    throw new NumberFormatException("out of range port");

            }
            catch (Exception e)
            {
                errorMessage = LocaleUtils.getLocalizedString(
                    "config.page.configuration.error.minport",
                    "jitsivideobridge");
            }
        }
        String maxPort = request.getParameter("maxport");
        if (maxPort != null) {
            maxPort = maxPort.trim();
            try
            {
                int port = Integer.valueOf(maxPort);

                if(port >= 1 && port <= 65535)
                    JiveGlobals.setProperty(
                        PluginImpl.MAX_PORT_NUMBER_PROPERTY_NAME, maxPort);
                else
                    throw new NumberFormatException("out of range port");
            }
            catch (Exception e)
            {
                errorMessage = LocaleUtils.getLocalizedString(
                    "config.page.configuration.error.maxport",
                    "jitsivideobridge");
            }
        }
    }

%>
<html>
<head>
   <title><fmt:message key="config.page.title" /></title>

   <meta name="pageID" content="jitsi-videobridge-settings"/>
</head>
<body>
<% if (errorMessage != null) { %>
<div class="jive-error">
    <table cellpadding="0" cellspacing="0" border="0">
        <tbody>
        <tr>
            <td class="jive-icon"><img src="/images/error-16x16.gif" width="16" height="16" border="0" alt=""/></td>
            <td class="jive-icon-label">
                <%= errorMessage%>
            </td>
        </tr>
        </tbody>
    </table>
</div>
<br/>
<% } %>

<p>
    <fmt:message key="config.page.description"/>
</p>
<form action="jitsi-videobridge.jsp" method="post">
    <div class="jive-contentBoxHeader">
        <fmt:message key="config.page.configuration.title"/>
    </div>
    <div class="jive-contentBox">
        <table cellpadding="0" cellspacing="0" border="0">
            <tbody>
            <tr>
                <td><label class="jive-label"><fmt:message key="config.page.configuration.min.port"/>:</label><br>
                </td>
                <td align="left">
                    <input name="minport" type="text" maxlength="5" size="5"
                           value="<%=plugin.getMinPort()%>"/>
                </td>
            </tr>
            <tr>
                <td><label class="jive-label"><fmt:message key="config.page.configuration.max.port"/>:</label><br>
                </td>
                <td align="left">
                    <input name="maxport" type="text" maxlength="5" size="5"
                           value="<%=plugin.getMaxPort()%>"/>
                </td>
            </tr>
            </tbody>
        </table>
    </div>
    <input type="submit" name="update" value="<fmt:message key="config.page.configuration.submit" />">
</form>

</body>
</html>
