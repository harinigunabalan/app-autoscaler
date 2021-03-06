'use strict';
module.exports = function(settings,tlsOptions){
  var models = require('../models')(settings.db);
  var express = require('express');
  var router = express.Router();
  var logger = require('../log/logger');
  var HttpStatus = require('http-status-codes');
  var validationMiddleWare = require('../validation/validationMiddleware');
  var routeHelper = require('./routeHelper')(settings.db);
  var schedulerUtil = require('../utils/schedulerUtils')(settings, tlsOptions);
  var async = require('async');

  router.put('/:app_id',validationMiddleWare,function(req, res) {
    logger.info('Policy creation request received',{ 'app id': req.params.app_id });
    async.waterfall([async.apply(schedulerUtil.createOrUpdateSchedule, req),
      async.apply(routeHelper.createOrUpdatePolicy, req)],
      function(error, result) {
        var responseDecorator = { };
        var statusCode = HttpStatus.OK;
        if(error) {
          statusCode = error.statusCode;
          responseDecorator = {
            'success': false,
            'error': error,
            'result': null
          };
        }
        else {
          statusCode = result.statusCode;
          if(result.statusCode === HttpStatus.CREATED) {
            res.set('Location', '/v1/policies/' + req.params.app_id);
          }
          responseDecorator = {
            'success': true,
            'error': null,
            'result': result.response    
          }
        }
        res.status(statusCode).json(responseDecorator);
      });
  });

  router.delete('/:app_id',function(req,res) {
    logger.info('Policy deletion request received for application', { 'app id': req.params.app_id });
    async.waterfall([async.apply(routeHelper.deletePolicy, req),
                     async.apply(schedulerUtil.deleteSchedules, req)],
    function(error, result) {
      var responseDecorator = { };
      var status = HttpStatus.OK;
      if(error) {
        status = error.statusCode;
        responseDecorator = {
          'success': false,
          'error': error,
          'result': null
        };
      } 
      else {
        status = HttpStatus.OK;
      }
      res.status(status).json(responseDecorator);
    });
  });

  router.get('/:app_id',function(req,res) {
    logger.info('Request for policy details received',{ 'app id': req.params.app_id });
    models.policy_json.findById(req.params.app_id).then (function(policyExists) {
      if(policyExists) {
        logger.info('Policy details retrieved ', { 'app id': req.params.app_id });
        res.status(HttpStatus.OK).json(policyExists.policy_json);
      } 
      else{
        logger.info('No policy found',{ 'app id': req.params.app_id });
        res.status(HttpStatus.NOT_FOUND).json({});
      }
    }).catch(function(error) {
      logger.error ('Failed to retrieve policy details',
          { 'app id': req.params.app_id,'error':error });
      res.status(HttpStatus.INTERNAL_SERVER_ERROR).json(error);
    });
  });

  return router;
}

