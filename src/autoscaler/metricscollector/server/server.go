package server

import (
	"fmt"
	"net/http"

	"autoscaler/cf"
	"autoscaler/db"
	"autoscaler/metricscollector/config"
	"autoscaler/metricscollector/noaa"
	"autoscaler/routes"

	"code.cloudfoundry.org/cfhttp"
	"code.cloudfoundry.org/lager"
	"github.com/gorilla/mux"
	"github.com/tedsuo/ifrit"
	"github.com/tedsuo/ifrit/http_server"
)

type VarsFunc func(w http.ResponseWriter, r *http.Request, vars map[string]string)

func (vh VarsFunc) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	vh(w, r, vars)
}

func NewServer(logger lager.Logger, conf *config.Config, cfc cf.CfClient, consumer noaa.NoaaConsumer, database db.InstanceMetricsDB) (ifrit.Runner, error) {
	mmh := NewMemoryMetricHandler(logger, cfc, consumer, database)

	r := routes.MetricsCollectorRoutes()
	r.Get(routes.MemoryMetricRoute).Methods(http.MethodGet).Handler(VarsFunc(mmh.GetMemoryMetric))
	r.Get(routes.MemoryMetricHistoryRoute).Methods(http.MethodGet).Handler(VarsFunc(mmh.GetMemoryMetricHistories))

	addr := fmt.Sprintf("0.0.0.0:%d", conf.Server.Port)
	logger.Info("new-http-server", lager.Data{"serverConfig": conf.Server})

	if (conf.Server.TLS.KeyFile != "") && (conf.Server.TLS.CertFile != "") {
		tlsConfig, err := cfhttp.NewTLSConfig(conf.Server.TLS.CertFile, conf.Server.TLS.KeyFile, conf.Server.TLS.CACertFile)
		if err != nil {
			logger.Error("failed-new-server-new-tls-config", err, lager.Data{"tls": conf.Server.TLS})
			return nil, err
		}
		return http_server.NewTLSServer(addr, r, tlsConfig), nil
	}

	return http_server.New(addr, r), nil

}
