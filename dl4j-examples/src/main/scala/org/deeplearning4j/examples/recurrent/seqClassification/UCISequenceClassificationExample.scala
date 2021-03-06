package org.deeplearning4j.examples.recurrent.seqClassification

import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.datavec.api.berkeley.Pair
import org.datavec.api.records.reader.SequenceRecordReader
import org.datavec.api.records.reader.impl.csv.CSVSequenceRecordReader
import org.datavec.api.split.NumberedFileInputSplit
import org.deeplearning4j.datasets.datavec.SequenceRecordReaderDataSetIterator
import org.deeplearning4j.eval.Evaluation
import org.deeplearning4j.nn.api.OptimizationAlgorithm
import org.deeplearning4j.nn.conf.GradientNormalization
import org.deeplearning4j.nn.conf.MultiLayerConfiguration
import org.deeplearning4j.nn.conf.NeuralNetConfiguration
import org.deeplearning4j.nn.conf.Updater
import org.deeplearning4j.nn.conf.layers.GravesLSTM
import org.deeplearning4j.nn.conf.layers.RnnOutputLayer
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork
import org.deeplearning4j.nn.weights.WeightInit
import org.deeplearning4j.optimize.listeners.ScoreIterationListener
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator
import org.nd4j.linalg.dataset.api.preprocessor.DataNormalization
import org.nd4j.linalg.dataset.api.preprocessor.NormalizerStandardize
import org.nd4j.linalg.lossfunctions.LossFunctions
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io.File
import java.net.URL
import java.util.ArrayList
import java.util.Collections
import java.util.List
import java.util.Random

import scala.collection.JavaConverters._

/**
 * Sequence Classification Example Using a LSTM Recurrent Neural Network
 *
 * This example learns how to classify univariate time series as belonging to one of six categories.
 * Categories are: Normal, Cyclic, Increasing trend, Decreasing trend, Upward shift, Downward shift
 *
 * Data is the UCI Synthetic Control Chart Time Series Data Set
 * Details:     https://archive.ics.uci.edu/ml/datasets/Synthetic+Control+Chart+Time+Series
 * Data:        https://archive.ics.uci.edu/ml/machine-learning-databases/synthetic_control-mld/synthetic_control.data
 * Image:       https://archive.ics.uci.edu/ml/machine-learning-databases/synthetic_control-mld/data.jpeg
 *
 * This example proceeds as follows:
 * 1. Download and prepare the data (in downloadUCIData() method)
 *    (a) Split the 600 sequences into train set of size 450, and test set of size 150
 *    (b) Write the data into a format suitable for loading using the CSVSequenceRecordReader for sequence classification
 *        This format: one time series per file, and a separate file for the labels.
 *        For example, train/features/0.csv is the features using with the labels file train/labels/0.csv
 *        Because the data is a univariate time series, we only have one column in the CSV files. Normally, each column
 *        would contain multiple values - one time step per row.
 *        Furthermore, because we have only one label for each time series, the labels CSV files contain only a single value
 *
 * 2. Load the training data using CSVSequenceRecordReader (to load/parse the CSV files) and SequenceRecordReaderDataSetIterator
 *    (to convert it to DataSet objects, ready to train)
 *    For more details on this step, see: http://deeplearning4j.org/usingrnns#data
 *
 * 3. Normalize the data. The raw data contain values that are too large for effective training, and need to be normalized.
 *    Normalization is conducted using NormalizerStandardize, based on statistics (mean, st.dev) collected on the training
 *    data only. Note that both the training data and test data are normalized in the same way.
 *
 * 4. Configure the network
 *    The data set here is very small, so we can't afford to use a large network with many parameters.
 *    We are using one small LSTM layer and one RNN output layer
 *
 * 5. Train the network for 40 epochs
 *    At each epoch, evaluate and print the accuracy and f1 on the test set
 *
 * @author Alex Black
 */
object UCISequenceClassificationExample {
    private lazy val log: Logger = LoggerFactory.getLogger(UCISequenceClassificationExample.getClass)

    //'baseDir': Base directory for the data. Change this if you want to save the data somewhere else
    private val baseDir = new File("src/main/resources/uci/")
    private val baseTrainDir = new File(baseDir, "train")
    private val featuresDirTrain = new File(baseTrainDir, "features")
    private val labelsDirTrain = new File(baseTrainDir, "labels")
    private val baseTestDir = new File(baseDir, "test")
    private val featuresDirTest = new File(baseTestDir, "features")
    private val labelsDirTest = new File(baseTestDir, "labels")

    @throws[Exception]
    def main(args: Array[String]) {
        downloadUCIData()

        // ----- Load the training data -----
        //Note that we have 450 training files for features: train/features/0.csv through train/features/449.csv
        val trainFeatures: SequenceRecordReader  = new CSVSequenceRecordReader()
        trainFeatures.initialize(new NumberedFileInputSplit(featuresDirTrain.getAbsolutePath + "/%d.csv", 0, 449))
        val trainLabels: SequenceRecordReader = new CSVSequenceRecordReader()
        trainLabels.initialize(new NumberedFileInputSplit(labelsDirTrain.getAbsolutePath + "/%d.csv", 0, 449))

        val miniBatchSize = 10
        val numLabelClasses = 6
        val trainData: DataSetIterator = new SequenceRecordReaderDataSetIterator(trainFeatures, trainLabels, miniBatchSize, numLabelClasses,
            false, SequenceRecordReaderDataSetIterator.AlignmentMode.ALIGN_END)

        //Normalize the training data
        val normalizer: DataNormalization = new NormalizerStandardize()
        normalizer.fit(trainData)              //Collect training data statistics
        trainData.reset()

        //Use previously collected statistics to normalize on-the-fly. Each DataSet returned by 'trainData' iterator will be normalized
        trainData.setPreProcessor(normalizer)


        // ----- Load the test data -----
        //Same process as for the training data.
        val testFeatures: SequenceRecordReader = new CSVSequenceRecordReader()
        testFeatures.initialize(new NumberedFileInputSplit(featuresDirTest.getAbsolutePath + "/%d.csv", 0, 149))
        val testLabels: SequenceRecordReader = new CSVSequenceRecordReader()
        testLabels.initialize(new NumberedFileInputSplit(labelsDirTest.getAbsolutePath + "/%d.csv", 0, 149))

        val testData: DataSetIterator = new SequenceRecordReaderDataSetIterator(testFeatures, testLabels, miniBatchSize, numLabelClasses,
            false, SequenceRecordReaderDataSetIterator.AlignmentMode.ALIGN_END)

        testData.setPreProcessor(normalizer);   //Note that we are using the exact same normalization process as the training data


        // ----- Configure the network -----
        val conf: MultiLayerConfiguration = new NeuralNetConfiguration.Builder()
                .seed(123)    //Random number generator seed for improved repeatability. Optional.
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).iterations(1)
                .weightInit(WeightInit.XAVIER)
                .updater(Updater.NESTEROVS).momentum(0.9)
                .learningRate(0.005)
                .gradientNormalization(GradientNormalization.ClipElementWiseAbsoluteValue)  //Not always required, but helps with this data set
                .gradientNormalizationThreshold(0.5)
                .list()
                .layer(0, new GravesLSTM.Builder().activation("tanh").nIn(1).nOut(10).build())
                .layer(1, new RnnOutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                        .activation("softmax").nIn(10).nOut(numLabelClasses).build())
                .pretrain(false).backprop(true).build()

        val net: MultiLayerNetwork = new MultiLayerNetwork(conf)
        net.init()

        net.setListeners(new ScoreIterationListener(20))   //Print the score (loss function value) every 20 iterations


        // ----- Train the network, evaluating the test set performance at each epoch -----
        val nEpochs = 40
        val str =
        (0 until nEpochs).foreach { i =>
            net.fit(trainData)

            //Evaluate on the test set:
            val evaluation = net.evaluate(testData)
            log.info(f"Test set evaluation at epoch $i: Accuracy = ${evaluation.accuracy()}%.2f, F1 = ${evaluation.f1()}%.2f")

            testData.reset()
            trainData.reset()
        }

        log.info("----- Example Complete -----")
    }


    //This method downloads the data, and converts the "one time series per line" format into a suitable
    //CSV sequence format that DataVec (CsvSequenceRecordReader) and DL4J can read.
    @throws[Exception]
    private[this] def downloadUCIData() {
        if (baseDir.exists()) return;    //Data already exists, don't download it again

        val url = "https://archive.ics.uci.edu/ml/machine-learning-databases/synthetic_control-mld/synthetic_control.data"
        val data = IOUtils.toString(new URL(url))

        val lines = data.split("\n")

        //Create directories
        baseDir.mkdir()
        baseTrainDir.mkdir()
        featuresDirTrain.mkdir()
        labelsDirTrain.mkdir()
        baseTestDir.mkdir()
        featuresDirTest.mkdir()
        labelsDirTest.mkdir()

        var lineCount = 0
        val contentAndLabels = new ArrayList[Pair[String, Integer]]()

        lines.foreach { line =>
            val transposed = line.replaceAll(" +", "\n")

            //Labels: first 100 examples (lines) are label 0, second 100 examples are label 1, and so on
            contentAndLabels.add(new Pair(transposed, lineCount / 100))
            lineCount += 1
        }

        //Randomize and do a train/test split:
        Collections.shuffle(contentAndLabels, new Random(12345))

        val nTrain = 450   //75% train, 25% test
        var trainCount = 0
        var testCount = 0
        contentAndLabels.asScala.foreach { p =>
            //Write output in a format we can read, in the appropriate locations
            val (outPathFeatures, outPathLabels) = if (trainCount < nTrain) {
                val a = new File(featuresDirTrain, trainCount + ".csv")
                val b = new File(labelsDirTrain, trainCount + ".csv")
                trainCount += 1
                (a, b)
            } else {
                val a = new File(featuresDirTest, testCount + ".csv")
                val b = new File(labelsDirTest, testCount + ".csv")
                testCount += 1
                (a, b)
            }

            FileUtils.writeStringToFile(outPathFeatures, p.getFirst)
            FileUtils.writeStringToFile(outPathLabels, p.getSecond.toString)
        }
    }
}
