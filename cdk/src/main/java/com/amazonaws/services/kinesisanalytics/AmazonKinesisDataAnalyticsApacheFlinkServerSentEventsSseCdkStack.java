package com.amazonaws.services.kinesisanalytics;

import software.amazon.awscdk.core.*;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.kinesis.Stream;
import software.amazon.awscdk.services.kinesis.StreamEncryption;
import software.amazon.awscdk.services.kinesis.analytics.flink.Application;
import software.amazon.awscdk.services.kinesis.analytics.flink.ApplicationCode;
import software.amazon.awscdk.services.kinesis.analytics.flink.Runtime;
import software.amazon.awscdk.services.kinesisfirehose.DeliveryStream;
import software.amazon.awscdk.services.kinesisfirehose.destinations.S3Bucket;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.SingletonFunction;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.IBucket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AmazonKinesisDataAnalyticsApacheFlinkServerSentEventsSseCdkStack extends Stack {
    private final boolean createVPC;
    private CfnParameter s3BucketParam;
    private CfnParameter s3StorageBucketParam;
    private CfnParameter filenameParam;
    private CfnParameter s3StoragePrefixParam;
    private CfnParameter s3StorageErrorPrefixParam;
    private CfnParameter subnetIdsParam;
    private CfnParameter securityGroupIdsParam;
    private Stream outputDataStream;
    private IBucket s3Bucket;
    private IBucket s3StorageBucket;
    private Vpc vpc;
    private SecurityGroup securityGroup;

    public AmazonKinesisDataAnalyticsApacheFlinkServerSentEventsSseCdkStack(final Construct scope, final String id, boolean createVPC) {
        this(scope, id, null, createVPC);
    }

    public AmazonKinesisDataAnalyticsApacheFlinkServerSentEventsSseCdkStack(final Construct scope, final String id, final StackProps props, boolean createVPC) {
        super(scope, id, props);
        this.createVPC = createVPC;
        createParameters();
        lookupBuckets();
        if (this.createVPC) {
            createVPC();
        }
        createKinesisDataStream();
        createKinesisFirehose();
        createKinesisDataAnalyticsApplication();
    }

    private void createParameters() {
        s3BucketParam = CfnParameter.Builder.create(this, "S3Bucket")
                .type("String")
                .description("The S3 bucket where the Amazon Kinesis Data Analytics application gets your application's JAR file")
                .allowedPattern(".+")
                .build();
        s3StorageBucketParam = CfnParameter.Builder.create(this, "S3StorageBucket")
                .type("String")
                .description("The S3 bucket name used to store the server-sent events data")
                .allowedPattern(".+")
                .build();
        s3StoragePrefixParam = CfnParameter.Builder.create(this, "S3StorageBucketPrefix")
                .type("String")
                .description("The prefix used when storing server-sent events data into the S3 bucket")
                .defaultValue("sse-data")
                .build();
        s3StorageErrorPrefixParam = CfnParameter.Builder.create(this, "S3StorageBucketErrorPrefix")
                .type("String")
                .description("The prefix used when storing error events into the S3 bucket")
                .defaultValue("sse-error")
                .build();
        filenameParam = CfnParameter.Builder.create(this, "FlinkApplication")
                .type("String")
                .description("The Apache Flink application jar filename located in the S3 bucket")
                .defaultValue("amazon-kinesis-data-analytics-apache-flink-server-sent-events-1.0.0.jar")
                .allowedPattern(".+")
                .build();
        if (!this.createVPC) {
            subnetIdsParam = CfnParameter.Builder.create(this, "Subnets")
                    .type("List<AWS::EC2::Subnet::Id>")
                    .description("The subnet Ids used for the Amazon Kinesis Data Analytics application")
                    .build();
            securityGroupIdsParam = CfnParameter.Builder.create(this, "SecurityGroups")
                    .type("List<AWS::EC2::SecurityGroup::Id>")
                    .description("The security group Ids used for the Amazon Kinesis Data Analytics application")
                    .build();
        }
    }

    private void lookupBuckets() {
        s3Bucket = Bucket.fromBucketName(this, "S3BucketCheck", s3BucketParam.getValueAsString());
        s3StorageBucket = Bucket.fromBucketName(this, "S3StorageBucketCheck", s3StorageBucketParam.getValueAsString());
    }

    private void createVPC() {
        vpc = Vpc.Builder.create(this, "KinesisDataAnalyticsVPC")
                .cidr("10.0.0.0/16")
                .natGateways(1)
                .maxAzs(3)
                .subnetConfiguration(List.of(
                        SubnetConfiguration.builder().cidrMask(24).subnetType(SubnetType.PRIVATE).name("Kinesis-SSE-Private").build(),
                        SubnetConfiguration.builder().cidrMask(24).subnetType(SubnetType.PUBLIC).name("Kinesis-SSE-Public").build()))
                .build();
        securityGroup = SecurityGroup.Builder.create(this, "KinesisDataAnalyticsSecurityGroup")
                .vpc(vpc)
                .allowAllOutbound(true)
                .description("Security group for Server-Sent Events")
                .build();
    }

    private void createKinesisDataStream() {
        outputDataStream = Stream.Builder.create(this, "KinesisServerSentEventsDataStreamA")
                .shardCount(1)
                .retentionPeriod(Duration.hours(24))
                .encryption(StreamEncryption.UNENCRYPTED)
                .build();
    }

    private void createKinesisFirehose() {
        S3Bucket s3DestinationBucket = S3Bucket.Builder.create(s3StorageBucket)
                .bufferingInterval(Duration.seconds(60))
                .dataOutputPrefix(s3StoragePrefixParam.getValueAsString())
                .errorOutputPrefix(s3StorageErrorPrefixParam.getValueAsString())
                .bufferingSize(Size.mebibytes(5))
                .build();
        DeliveryStream.Builder.create(this, "KinesisFirehoseS3Delivery")
                .sourceStream(outputDataStream)
                .encryption(software.amazon.awscdk.services.kinesisfirehose.StreamEncryption.UNENCRYPTED)
                .destinations(List.of(
                        s3DestinationBucket
                ))
                .build();
    }

    /***
     * This method reads the contents of a file and returns it in UTF-8 encoding
     * @param filePath The full path to the file to read
     * @return The contents of the file
     */
    private static String readFile(String filePath) {
        StringBuilder contentBuilder = new StringBuilder();

        try (java.util.stream.Stream<String> stream = Files.lines(Paths.get(filePath), StandardCharsets.UTF_8)) {
            stream.forEach(s -> contentBuilder.append(s).append("\n"));
        } catch (IOException e) {
            e.printStackTrace();
        }

        return contentBuilder.toString();
    }

    private void createKinesisDataAnalyticsApplication() {
        Application application = Application.Builder.create(this, "KinesisAnalyticsServerSentEventsApplication")
                .parallelismPerKpu(1)
                .parallelism(1)
                .runtime(Runtime.FLINK_1_11)
                .code(ApplicationCode.fromBucket(s3Bucket, filenameParam.getValueAsString()))
                .autoScalingEnabled(false)
                .build();
        application.addToRolePolicy(PolicyStatement.Builder.create()
                .resources(List.of("*"))
                .actions(List.of(
                        "ec2:DescribeVpcs",
                        "ec2:DescribeSubnets",
                        "ec2:DescribeSecurityGroups",
                        "ec2:DescribeDhcpOptions"
                ))
                .effect(Effect.ALLOW)
                .build());
        application.addToRolePolicy(PolicyStatement.Builder.create()
                .resources(List.of("*"))
                .actions(List.of(
                        "ec2:CreateNetworkInterface",
                        "ec2:CreateNetworkInterfacePermission",
                        "ec2:DescribeNetworkInterfaces",
                        "ec2:DeleteNetworkInterface"
                ))
                .effect(Effect.ALLOW)
                .build());
        outputDataStream.grantReadWrite(application);

        String functionCode = readFile("lambda/KinesisAnalyticsSetup.js");

        Map<String, String> environmentProperties = new HashMap<>();
        environmentProperties.put("ApplicationName", application.getApplicationName());
        environmentProperties.put("OutputStream", outputDataStream.getStreamName());

        final SingletonFunction lambdaFunction = SingletonFunction.Builder.create(this, "KinesisAnalyticsInit")
                        .description("Initialize the Amazon Kinesis Data Analytics application")
                        .code(Code.fromInline(functionCode))
                        .handler("index.handler")
                        .timeout(Duration.seconds(30))
                        .runtime(software.amazon.awscdk.services.lambda.Runtime.NODEJS_12_X)
                        .uuid(java.util.UUID.randomUUID().toString())
                        .environment(environmentProperties)
                        .build();

        lambdaFunction.addToRolePolicy(PolicyStatement.Builder.create()
                .resources(List.of(application.getApplicationArn()))
                .actions(List.of(
                        "kinesisanalytics:UpdateApplication",
                        "kinesisanalytics:DescribeApplication",
                        "kinesisanalytics:AddApplicationVpcConfiguration"
                ))
                .effect(Effect.ALLOW)
                .build());

        createKinesisAnalyticsInit(lambdaFunction);
    }

    private void createKinesisAnalyticsInit(SingletonFunction lambdaFunction) {
        Map<String, Object> resourceProperties = new HashMap<>();
        Map<String, Object> vpcConfig = new HashMap<>();
        vpcConfig.put("SecurityGroupIds", this.createVPC ? List.of(securityGroup.getSecurityGroupId()) : securityGroupIdsParam.getValueAsList());
        vpcConfig.put("SubnetIds", this.createVPC ? List.of(vpc.getPrivateSubnets().get(0).getSubnetId()) : subnetIdsParam.getValueAsList());
        resourceProperties.put("VpcConfiguration", vpcConfig);

        CustomResource.Builder.create(this, "KinesisAnalyticsInitResourceVPC")
                    .properties(resourceProperties)
                    .serviceToken(lambdaFunction.getFunctionArn())
                    .build();
    }
}
