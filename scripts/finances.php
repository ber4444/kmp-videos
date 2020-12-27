<?php

require("lib/utils.php");
require("lib/nusoap.php");

$nsc = startEtapestrySession();

$request = array();
$request["start"] = 0;
$request["count"] = 6;
$request["accountRef"] = "3406.0.47459381";

echo "Calling getJournalEntries method...";
$response = $nsc->call("getJournalEntries", array($request));
echo "Done<br><br>";

checkStatus($nsc);

echo "Response: <pre>";
print_r($response);
echo "</pre>";

stopEtapestrySession($nsc);

?>
