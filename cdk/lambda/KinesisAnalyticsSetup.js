// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

const AWS = require('aws-sdk');
const response = require('cfn-response');
var kinesisanalyticsv2 = new AWS.KinesisAnalyticsV2();
exports.handler = (event, context) => {
    console.info("Event: " + JSON.stringify(event));
    if (event.RequestType == "Create") {
        try {
            kinesisanalyticsv2.describeApplication({
                ApplicationName: process.env.ApplicationName,
                IncludeAdditionalDetails: true
            }, function(err, app) {
                if (err) {
                    response.send(event, context, response.FAILED, err);
                }
                else {
                    kinesisanalyticsv2.updateApplication({
                        "ApplicationName": process.env.ApplicationName,
                        "CurrentApplicationVersionId": app.ApplicationDetail.ApplicationVersionId,
                        "ApplicationConfigurationUpdate": {
                            "EnvironmentPropertyUpdates": {
                                "PropertyGroups": [
                                    {
                                        "PropertyGroupId": "ProducerConfigProperties",
                                        "PropertyMap": {
                                            "AggregationEnabled" : "false",
                                            "aws.region" : process.env.AWS_REGION
                                        }
                                    },
                                    {
                                        "PropertyGroupId": "OutputStreamProperties",
                                        "PropertyMap": {
                                            "DefaultStream" : process.env.OutputStream
                                        }
                                    },
                                    {
                                        "PropertyGroupId": "SSESourceProperties",
                                        "PropertyMap": {
                                            "url" : "https://stream.wikimedia.org/v2/stream/recentchange"
                                        }
                                    }
                                ]
                            }
                        }
                    }, function (err, update) {
                        if (err) {
                            response.send(event, context, response.FAILED, err);
                        } else {
                            kinesisanalyticsv2.addApplicationVpcConfiguration({
                                "ApplicationName": process.env.ApplicationName,
                                "VpcConfiguration": event.ResourceProperties.VpcConfiguration,
                                "CurrentApplicationVersionId": app.ApplicationDetail.ApplicationVersionId + 1
                            }, function (err, vpcConfig) {
                                if (err) {
                                    response.send(event, context, response.FAILED, err);
                                } else {
                                    response.send(event, context, response.SUCCESS, {});
                                }
                            });
                        }
                    });
                }
            });
        }
        catch (err) {
            response.send(event, context, response.FAILED, err);
        }
    }
    else {
        response.send(event, context, response.SUCCESS, {});
    }
};