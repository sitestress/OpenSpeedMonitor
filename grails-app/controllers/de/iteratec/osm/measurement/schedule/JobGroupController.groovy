/*
* OpenSpeedMonitor (OSM)
* Copyright 2014 iteratec GmbH
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* 	http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package de.iteratec.osm.measurement.schedule

import de.iteratec.osm.csi.CsiConfiguration
import de.iteratec.osm.csi.transformation.DefaultTimeToCsMappingService
import de.iteratec.osm.d3Data.BarChartData
import de.iteratec.osm.d3Data.ChartEntry
import de.iteratec.osm.d3Data.MatrixViewData
import de.iteratec.osm.d3Data.MatrixViewEntry
import de.iteratec.osm.d3Data.MultiLineChart
import de.iteratec.osm.d3Data.TreemapData
import de.iteratec.osm.measurement.environment.Browser
import de.iteratec.osm.util.I18nService
import grails.converters.JSON


/**
 * JobGroupController
 * A controller class handles incoming web requests and performs actions such as redirects, rendering views and so on.
 */
class JobGroupController {
    static scaffold = true

    I18nService i18nService
    DefaultTimeToCsMappingService defaultTimeToCsMappingService

    def show() {
        def jobGroupInstance = JobGroup.get(params.id)
        def modelToRender = [:]

        CsiConfiguration config = jobGroupInstance.csiConfiguration

        if (config) {
            //Labels for charts
            String zeroWeightLabel = i18nService.msg("de.iteratec.osm.d3Data.treemap.zeroWeightLabel", "Pages ohne Gewichtung")
            String dataLabel = i18nService.msg("de.iteratec.osm.d3Data.treemap.dataLabel", "Page")
            String weightLabel = i18nService.msg("de.iteratec.osm.d3Data.treemap.weightLabel", "Gewichtung")
            String xAxisLabel = i18nService.msg("de.iteratec.osm.d3Data.barChart.xAxisLabel", "Tageszeit")
            String yAxisLabel = i18nService.msg("de.iteratec.osm.d3Data.barChart.yAxisLabel", "Gewichtung")
            String matrixViewXLabel = i18nService.msg("de.iteratec.osm.d3Data.matrixView.xLabel", "Browser")
            String matrixViewYLabel = i18nService.msg("de.iteratec.osm.d3Data.matrixView.yLabel", "Conn")
            String matrixViewWeightLabel = i18nService.msg("de.iteratec.osm.d3Data.matrixView.weightLabel", "Weight")
            String colorBrightLabel = i18nService.msg("de.iteratec.osm.d3Data.matrixView.colorBrightLabel", "less")
            String colorDarkLabel = i18nService.msg("de.iteratec.osm.d3Data.matrixView.colorDarkLabel", "more")
            String matrixZeroWeightLabel = i18nService.msg("de.iteratec.osm.d3Data.matrixView.zeroWeightLabel", "Im CSI nicht berücksichtigt")

            // arrange matrixViewData
            MatrixViewData matrixViewData = new MatrixViewData(weightLabel: matrixViewWeightLabel, rowLabel: matrixViewYLabel, columnLabel: matrixViewXLabel, colorBrightLabel: colorBrightLabel, colorDarkLabel: colorDarkLabel, zeroWeightLabel: matrixZeroWeightLabel)
            matrixViewData.addColumns(Browser.findAll()*.name as Set)
            matrixViewData.addRows(ConnectivityProfile.findAll()*.name as Set)
            config.browserConnectivityWeights.each {
                matrixViewData.addEntry(new MatrixViewEntry(weight: it.weight, columnName: it.browser.name, rowName: it.connectivity.name))
            }
            def matrixViewDataJSON = matrixViewData as JSON

            // arrange treemap data
            TreemapData treemapData = new TreemapData(zeroWeightLabel: zeroWeightLabel, dataName: dataLabel, weightName: weightLabel);
            config.pageWeights.each { pageWeight -> treemapData.addNode(new ChartEntry(name: pageWeight.page.name, weight: pageWeight.weight)) }
            def treemapDataJSON = treemapData as JSON

            // arrange barchart data
            BarChartData barChartData = new BarChartData(xLabel: xAxisLabel, yLabel: yAxisLabel)
            config.day.hoursOfDay.sort { a, b -> a.fullHour - b.fullHour }.each { h -> barChartData.addDatum(new ChartEntry(name: h.fullHour.toString(), weight: h.weight)) }
            def barChartJSON = barChartData as JSON

            MultiLineChart defaultTimeToCsMappingsChart = defaultTimeToCsMappingService.getDefaultMappingsAsChart(10000)
            String selectedCsiConfiguration = config.label

            modelToRender = [matrixViewData: matrixViewDataJSON,
                             treemapData   : treemapDataJSON,
                             barchartData  : barChartJSON,
                             defaultTimeToCsMappings : defaultTimeToCsMappingsChart as JSON]
        }

        modelToRender.put("jobGroupInstance", jobGroupInstance)

        return modelToRender
    }

    def update() {
        def jobGroupInstance = JobGroup.get(params.id)
        if (!jobGroupInstance) {
            flash.message = message(code: 'default.not.found.message', args: [message(code: 'jobGroup.label', default: 'JobGroup'), params.id])
            redirect(action: "list")
            return
        }
        if (!(params?.graphiteServers)) {
            jobGroupInstance.graphiteServers.clear()
        }
        if (params.version) {
            def version = params.version.toLong()
            if (jobGroupInstance.version > version) {
                jobGroupInstance.errors.rejectValue("version", "default.optimistic.locking.failure",
                        [message(code: 'jobGroup.label', default: 'JobGroup')] as Object[],
                        "Another user has updated this JobGroup while you were editing")
                render(view: "edit", model: [jobGroupInstance: jobGroupInstance])
                return
            }
        }
        String csiConfigLabel = params.remove("csiConfiguration")
        if(csiConfigLabel != null) {
            CsiConfiguration config = CsiConfiguration.findByLabel(csiConfigLabel)
            jobGroupInstance.csiConfiguration = config
        }

        jobGroupInstance.properties = params

        if (!jobGroupInstance.save(flush: true)) {
            render(view: "edit", model: [jobGroupInstance: jobGroupInstance])
            return
        }

        flash.message = message(code: 'default.updated.message', args: [message(code: 'jobGroup.label', default: 'JobGroup'), jobGroupInstance.id])
        redirect(action: "show", id: jobGroupInstance.id)
    }
}
