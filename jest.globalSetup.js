// Run every test in a time zone with daylight saving time, so that day-boundary tests
// cover 23- and 25-hour days. Jest workers start after this and inherit the variable.
module.exports = () => {
  process.env.TZ = 'America/New_York';
};
