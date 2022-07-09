const path = require('path');

module.exports = {
  entry: {
    main: './src/index.js',
    process: './src/process.js',
  },
  mode: 'development',
  output: {
    path: path.resolve(__dirname, 'dist'),
    filename: '[name]-bundle.js',
    library: ["MyLibrary", "[name]"],
    libraryTarget: "umd",
    globalObject: 'this'
  }
};
