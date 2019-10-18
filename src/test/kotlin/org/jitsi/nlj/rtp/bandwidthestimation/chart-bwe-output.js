// Load the Visualization API and the corechart package.
google.charts.load('current', {'packages':['corechart', 'scatter']});

// Set a callback to run when the Google Visualization API is loaded.
google.charts.setOnLoadCallback(drawCharts);

// Callback that creates and populates a data table,
// instantiates the pie chart, passes in the data and
// draws it.
function drawChart(chart) {
    // Create the data table.
    var data = google.visualization.arrayToDataTable(chart.data);

    var options = {
	width: 800,
	height: 600,
	title: chart.title,

	series: {},
	vAxes: {},

	pointSize: 0,
	lineWidth: 1,
	interpolateNulls: true,
    }

    var series = 0;
    var msAxis;
    if (chart.columns.bw) {
	options.series["0"] = {targetAxisIndex: 0};
	options.vAxes["0"] = {title: 'Bandwidth (bps)', minValue:0};
	msAxis = 1;
	series++;
    }
    else {
	msAxis = 0;
    }
    if (chart.columns.rtt || chart.columns.delay) {
	options.vAxes[msAxis] = {title: 'ms', minValue:0};
    }
    if (chart.columns.rtt) {
	options.series[series] = {targetAxisIndex: msAxis};
	series++;
    }
    if (chart.columns.delay) {
	options.series[series] = {targetAxisIndex: msAxis};
	series++;
    }

    var div = document.createElement("div");
    document.getElementById('chart_div').appendChild(div);
    var chart = new google.visualization.ScatterChart(div);
    chart.draw(data, options);
}

function drawCharts() {
    var chart;
    for (chart in charts) {
	drawChart(charts[chart]);
    }
}
