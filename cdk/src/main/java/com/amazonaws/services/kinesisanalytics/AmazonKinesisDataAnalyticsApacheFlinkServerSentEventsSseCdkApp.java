// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.services.kinesisanalytics;

import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.StackProps;

public class AmazonKinesisDataAnalyticsApacheFlinkServerSentEventsSseCdkApp {
    public static void main(final String[] args) {
        App app = new App();

        new AmazonKinesisDataAnalyticsApacheFlinkServerSentEventsSseCdkStack(
                app,
                "AmazonKinesisDataAnalyticsApacheFlinkServerSentEventsSseCreateVPC",
                StackProps.builder().build(),
                true
        );
        new AmazonKinesisDataAnalyticsApacheFlinkServerSentEventsSseCdkStack(
                app,
                "AmazonKinesisDataAnalyticsApacheFlinkServerSentEventsSseUseExistingVPC",
                StackProps.builder().build(),
                false
        );
        app.synth();
    }
}
