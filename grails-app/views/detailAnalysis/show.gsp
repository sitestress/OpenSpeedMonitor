<%@ page defaultCodec="none" %></page>
<%@ page contentType="text/html;charset=UTF-8" %>
<html>
<head>
    <meta name="layout" content="kickstart_osm"/>
    <title><g:message code="de.iteratec.isocsi.detailAnalysis"/></title>
</head>

<body>

<%-- main menu --%>
<g:render template="/layouts/mainMenu"/>
<div class="row">
    <div class="span12">
        <g:if test="${errorList && !errorList.empty}">
            <div class="alert alert-error">
                <strong><g:message code="de.iteratec.isocsi.CsiDashboardController.selectionErrors.title"/></strong>
                <ul>
                    <g:each in="${errorList}">
                        <li><g:message error = "${it}"/></li>
                    </g:each>
                </ul>
            </div>
        </g:if>
    </div>
</div>

<a class="btn btn-primary" href="${createLink(controller: 'eventResultDashboard', action: 'showAll', params: params)}">
    <i class="fa fa-caret-left" aria-hidden="true"></i> <g:message message="de.iteratec.osm.detailAnalysis.backButton" default="Back" />
</a>

<g:if test="${osmDetailAnalysisRequest}">
    <div id="detailDatenContainer">${osmDetailAnalysisRequest}</div>
</g:if>
<content tag="include.bottom">
    <asset:javascript src="eventresultdashboard/eventResultDashboard.js"/>
</content>

</body>
</html>
